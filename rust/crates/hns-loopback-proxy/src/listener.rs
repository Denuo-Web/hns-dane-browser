//! Owned blocking listener lifecycle for the loopback proxy.

use crate::backend::CancellationToken;
use crate::rate_limit::ActiveClientLimiter;
use std::cell::Cell;
use std::collections::HashMap;
use std::io;
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};
use std::time::Duration;

const ACCEPT_RETRY_INTERVAL: Duration = Duration::from_millis(20);

pub(crate) type ClientHandler =
    dyn Fn(TcpStream, SocketAddr, CancellationToken) + Send + Sync + 'static;
pub(crate) type RejectionHandler =
    dyn Fn(TcpStream, SocketAddr, CancellationToken) + Send + Sync + 'static;

/// A loopback listener whose accept thread owns every client worker.
///
/// Client handlers must finish when their cancellation token is cancelled or
/// their stream is shut down. From an external control thread,
/// [`OwnedListener::stop`] joins all work before returning instead of leaving
/// detached threads behind. A callback may request a reentrant stop, which
/// returns after cancellation so the callback can unwind; the external owner
/// must retain the listener and later join it through `stop` or `drop`.
pub(crate) struct OwnedListener {
    local_addr: SocketAddr,
    cancellation: CancellationToken,
    limiter: ActiveClientLimiter,
    controls: Arc<ClientControls>,
    accept_thread: AcceptThread,
}

impl OwnedListener {
    pub(crate) fn start(
        bind_addr: SocketAddr,
        max_active_clients: usize,
        cancellation: CancellationToken,
        client_handler: Arc<ClientHandler>,
        rejection_handler: Arc<RejectionHandler>,
    ) -> io::Result<Self> {
        Self::start_with_spawner(
            bind_addr,
            max_active_clients,
            cancellation,
            client_handler,
            rejection_handler,
            |builder, task| builder.spawn(task),
        )
    }

    fn start_with_spawner<S>(
        bind_addr: SocketAddr,
        max_active_clients: usize,
        cancellation: CancellationToken,
        client_handler: Arc<ClientHandler>,
        rejection_handler: Arc<RejectionHandler>,
        spawn: S,
    ) -> io::Result<Self>
    where
        S: FnOnce(thread::Builder, AcceptTask) -> io::Result<JoinHandle<()>>,
    {
        if !bind_addr.ip().is_loopback() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "proxy listener address must be loopback",
            ));
        }
        if cancellation.is_cancelled() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "proxy listener cancellation token is already cancelled",
            ));
        }

        let listener = TcpListener::bind(bind_addr)?;
        listener.set_nonblocking(true)?;
        let local_addr = listener.local_addr()?;
        let limiter = ActiveClientLimiter::new(max_active_clients);
        let controls = Arc::new(ClientControls::default());

        let task_cancellation = cancellation.clone();
        let task_limiter = limiter.clone();
        let task_controls = Arc::clone(&controls);
        let task: AcceptTask = Box::new(move || {
            accept_loop(
                listener,
                task_cancellation,
                task_limiter,
                task_controls,
                client_handler,
                rejection_handler,
            );
        });
        let handle = spawn(
            thread::Builder::new().name("hns-loopback-accept".to_owned()),
            task,
        )?;

        Ok(Self {
            local_addr,
            cancellation,
            limiter,
            controls,
            accept_thread: AcceptThread::new(handle),
        })
    }

    pub(crate) fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    pub(crate) fn active_clients(&self) -> usize {
        self.limiter.active()
    }

    /// Stops admission, wakes active socket operations, and joins all work
    /// when called by the external owner. A reentrant callback call requests
    /// stop without joining, avoiding accept/worker self-join cycles. Calling
    /// this method more than once is safe. Returns whether all owned threads
    /// were joined by this call (`false` only for a reentrant callback call).
    pub(crate) fn stop(&self) -> bool {
        self.cancellation.cancel();
        shutdown_controls(self.controls.begin_stop());
        // Joining from the accept thread would self-join. Joining from one of
        // its workers would cycle because accept-loop cleanup joins workers.
        // Cancellation is enough here; the external owner later joins the
        // accept thread through its ordinary stop/drop path.
        if OwnedThreadMarker::is_current(&self.controls) {
            return false;
        }
        self.accept_thread.join();
        true
    }
}

impl Drop for OwnedListener {
    fn drop(&mut self) {
        let _joined = self.stop();
    }
}

type AcceptTask = Box<dyn FnOnce() + Send + 'static>;

struct AcceptThread {
    state: Mutex<AcceptThreadState>,
    joined: Condvar,
}

struct AcceptThreadState {
    handle: Option<JoinHandle<()>>,
    joining: bool,
}

impl AcceptThread {
    fn new(handle: JoinHandle<()>) -> Self {
        Self {
            state: Mutex::new(AcceptThreadState {
                handle: Some(handle),
                joining: false,
            }),
            joined: Condvar::new(),
        }
    }

    fn join(&self) {
        let handle = {
            let mut state = lock_recover(&self.state);
            loop {
                if let Some(handle) = state.handle.take() {
                    state.joining = true;
                    break Some(handle);
                }
                if !state.joining {
                    break None;
                }
                state = wait_recover(&self.joined, state);
            }
        };

        let Some(handle) = handle else {
            return;
        };
        let _result = handle.join();

        let mut state = lock_recover(&self.state);
        state.joining = false;
        self.joined.notify_all();
    }
}

#[derive(Default)]
struct ClientControls {
    state: Mutex<ClientControlState>,
}

#[derive(Default)]
struct ClientControlState {
    stopping: bool,
    next_id: u64,
    streams: HashMap<u64, Arc<TcpStream>>,
}

impl ClientControls {
    fn register(self: &Arc<Self>, stream: Arc<TcpStream>) -> Option<ClientRegistration> {
        let mut state = lock_recover(&self.state);
        if state.stopping {
            return None;
        }

        let id = next_control_id(&mut state);
        state.streams.insert(id, stream);
        Some(ClientRegistration {
            id,
            controls: Arc::clone(self),
        })
    }

    fn unregister(&self, id: u64) {
        lock_recover(&self.state).streams.remove(&id);
    }

    fn begin_stop(&self) -> Vec<Arc<TcpStream>> {
        let mut state = lock_recover(&self.state);
        state.stopping = true;
        state.streams.values().cloned().collect()
    }
}

struct ClientRegistration {
    id: u64,
    controls: Arc<ClientControls>,
}

thread_local! {
    static OWNED_LISTENER_THREAD: Cell<*const ClientControls> =
        const { Cell::new(std::ptr::null()) };
}

struct OwnedThreadMarker {
    previous: *const ClientControls,
}

impl OwnedThreadMarker {
    fn enter(controls: &Arc<ClientControls>) -> Self {
        let identity = Arc::as_ptr(controls);
        let previous = OWNED_LISTENER_THREAD.with(|current| current.replace(identity));
        Self { previous }
    }

    fn is_current(controls: &Arc<ClientControls>) -> bool {
        let identity = Arc::as_ptr(controls);
        OWNED_LISTENER_THREAD.with(|current| current.get() == identity)
    }
}

impl Drop for OwnedThreadMarker {
    fn drop(&mut self) {
        OWNED_LISTENER_THREAD.with(|current| current.set(self.previous));
    }
}

impl Drop for ClientRegistration {
    fn drop(&mut self) {
        self.controls.unregister(self.id);
    }
}

fn next_control_id(state: &mut ClientControlState) -> u64 {
    loop {
        let candidate = state.next_id;
        state.next_id = state.next_id.wrapping_add(1);
        if !state.streams.contains_key(&candidate) {
            return candidate;
        }
    }
}

fn accept_loop(
    listener: TcpListener,
    cancellation: CancellationToken,
    limiter: ActiveClientLimiter,
    controls: Arc<ClientControls>,
    client_handler: Arc<ClientHandler>,
    rejection_handler: Arc<RejectionHandler>,
) {
    let _owned_thread = OwnedThreadMarker::enter(&controls);
    // In unwind-enabled builds this guard is also the unexpected-panic path:
    // it cancels, closes every registered socket, and joins every worker.
    let mut workers = WorkerSet::new(
        limiter.max_active(),
        cancellation.clone(),
        Arc::clone(&controls),
    );
    let mut next_worker_id = 0_u64;

    while !cancellation.is_cancelled() {
        workers.reap_finished();

        match listener.accept() {
            Ok((stream, peer_addr)) => {
                // BSD-family accept(2) implementations can inherit the
                // listener's O_NONBLOCK flag. Client handlers intentionally
                // use blocking I/O with explicit socket timeouts, so restore
                // that contract on every platform before dispatch.
                if stream.set_nonblocking(false).is_err() {
                    let _result = stream.shutdown(Shutdown::Both);
                    continue;
                }
                if cancellation.is_cancelled() {
                    let _result = stream.shutdown(Shutdown::Both);
                    break;
                }

                let control = match stream.try_clone() {
                    Ok(control) => Arc::new(control),
                    Err(_) => {
                        let _result = stream.shutdown(Shutdown::Both);
                        continue;
                    }
                };
                let Some(registration) = controls.register(control) else {
                    let _result = stream.shutdown(Shutdown::Both);
                    break;
                };

                let Some(permit) = limiter.try_acquire() else {
                    // A rejection response is deliberately handled on the
                    // accept thread. This keeps overload bounded rather than
                    // spawning an unlimited set of rejection workers.
                    let token = cancellation.clone();
                    let _result = catch_unwind(AssertUnwindSafe(|| {
                        rejection_handler(stream, peer_addr, token);
                    }));
                    drop(registration);
                    continue;
                };

                let handler = Arc::clone(&client_handler);
                let token = cancellation.clone();
                let worker_id = next_worker_id;
                next_worker_id = next_worker_id.wrapping_add(1);
                let spawn_result = thread::Builder::new()
                    .name(format!("hns-loopback-client-{worker_id}"))
                    .spawn(move || {
                        let _owned_thread = OwnedThreadMarker::enter(&registration.controls);
                        let _permit = permit;
                        let _registration = registration;
                        handler(stream, peer_addr, token);
                    });
                if let Ok(worker) = spawn_result {
                    workers.push(worker);
                }
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                cancellation.wait_cancelled_timeout(ACCEPT_RETRY_INTERVAL);
            }
            Err(error) if is_transient_accept_error(&error) => {}
            Err(_) => break,
        }
    }
}

fn is_transient_accept_error(error: &io::Error) -> bool {
    matches!(
        error.kind(),
        io::ErrorKind::Interrupted
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::ConnectionReset
    )
}

struct WorkerSet {
    handles: Vec<JoinHandle<()>>,
    cancellation: CancellationToken,
    controls: Arc<ClientControls>,
}

impl WorkerSet {
    fn new(
        capacity: usize,
        cancellation: CancellationToken,
        controls: Arc<ClientControls>,
    ) -> Self {
        Self {
            handles: Vec::with_capacity(capacity),
            cancellation,
            controls,
        }
    }

    fn push(&mut self, worker: JoinHandle<()>) {
        self.handles.push(worker);
    }

    fn reap_finished(&mut self) {
        reap_finished(&mut self.handles);
    }
}

impl Drop for WorkerSet {
    fn drop(&mut self) {
        self.cancellation.cancel();
        shutdown_controls(self.controls.begin_stop());
        for worker in self.handles.drain(..) {
            let _result = worker.join();
        }
    }
}

fn reap_finished(workers: &mut Vec<JoinHandle<()>>) {
    let mut index = 0;
    while index < workers.len() {
        if workers[index].is_finished() {
            let worker = workers.swap_remove(index);
            let _result = worker.join();
        } else {
            index += 1;
        }
    }
}

fn shutdown_controls(controls: Vec<Arc<TcpStream>>) {
    for stream in controls {
        let _result = stream.shutdown(Shutdown::Both);
    }
}

fn lock_recover<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn wait_recover<'a, T>(condvar: &Condvar, guard: MutexGuard<'a, T>) -> MutexGuard<'a, T> {
    condvar
        .wait(guard)
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::mpsc;
    use std::time::Duration;

    const TEST_TIMEOUT: Duration = Duration::from_secs(2);

    #[test]
    fn accepts_clients_and_releases_raii_permits() {
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let client_handler: Arc<ClientHandler> = Arc::new(move |mut stream, _, _| {
            started_tx.send(()).unwrap();
            let mut byte = [0_u8; 1];
            let _result = stream.read(&mut byte);
            finished_tx.send(()).unwrap();
        });
        let rejection_handler: Arc<RejectionHandler> = Arc::new(|_, _, _| {});
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            CancellationToken::new(),
            client_handler,
            rejection_handler,
        )
        .unwrap();

        let client = TcpStream::connect(listener.local_addr()).unwrap();
        started_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        assert_eq!(listener.active_clients(), 1);

        drop(client);
        finished_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        wait_for_active_count(&listener, 0);
        listener.stop();
    }

    #[test]
    fn accepted_client_streams_are_blocking() {
        let (started_tx, started_rx) = mpsc::channel();
        let (result_tx, result_rx) = mpsc::channel();
        let client_handler: Arc<ClientHandler> = Arc::new(move |mut stream, _, _| {
            started_tx.send(()).unwrap();
            let mut byte = [0_u8; 1];
            result_tx.send(stream.read(&mut byte)).unwrap();
        });
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            CancellationToken::new(),
            client_handler,
            Arc::new(|_, _, _| {}),
        )
        .unwrap();
        let mut client = TcpStream::connect(listener.local_addr()).unwrap();
        started_rx.recv_timeout(TEST_TIMEOUT).unwrap();

        assert!(result_rx.recv_timeout(Duration::from_millis(50)).is_err());
        client.write_all(b"x").unwrap();
        assert_eq!(result_rx.recv_timeout(TEST_TIMEOUT).unwrap().unwrap(), 1);
        listener.stop();
    }

    #[test]
    fn rejects_clients_beyond_the_active_limit_without_spawning_workers() {
        let (started_tx, started_rx) = mpsc::channel();
        let client_handler: Arc<ClientHandler> = Arc::new(move |mut stream, _, _| {
            started_tx.send(()).unwrap();
            let mut byte = [0_u8; 1];
            let _result = stream.read(&mut byte);
        });
        let (rejected_tx, rejected_rx) = mpsc::channel();
        let rejection_handler: Arc<RejectionHandler> = Arc::new(move |stream, _, _| {
            rejected_tx.send(()).unwrap();
            stream.shutdown(Shutdown::Both).unwrap();
        });
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            CancellationToken::new(),
            client_handler,
            rejection_handler,
        )
        .unwrap();

        let first_client = TcpStream::connect(listener.local_addr()).unwrap();
        started_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        let _second_client = TcpStream::connect(listener.local_addr()).unwrap();
        rejected_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        assert_eq!(listener.active_clients(), 1);

        drop(first_client);
        wait_for_active_count(&listener, 0);
        listener.stop();
    }

    #[test]
    fn stop_cancels_shuts_down_clients_joins_and_is_idempotent() {
        let cancellation = CancellationToken::new();
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let client_handler: Arc<ClientHandler> = Arc::new(move |mut stream, _, token| {
            started_tx.send(()).unwrap();
            let mut byte = [0_u8; 1];
            let _result = stream.read(&mut byte);
            assert!(token.is_cancelled());
            finished_tx.send(()).unwrap();
        });
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            cancellation.clone(),
            client_handler,
            Arc::new(|_, _, _| {}),
        )
        .unwrap();

        let _client = TcpStream::connect(listener.local_addr()).unwrap();
        started_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        listener.stop();
        listener.stop();

        assert!(cancellation.is_cancelled());
        assert_eq!(listener.active_clients(), 0);
        finished_rx.recv_timeout(TEST_TIMEOUT).unwrap();
    }

    #[test]
    fn stop_unblocks_a_stalled_partial_request_head() {
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let client_handler: Arc<ClientHandler> = Arc::new(move |mut stream, _, _| {
            started_tx.send(()).unwrap();
            let result = crate::http1::read_request_head(
                &mut stream,
                crate::http1::DEFAULT_MAX_REQUEST_HEAD_BYTES,
            );
            finished_tx.send(result.is_err()).unwrap();
        });
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            CancellationToken::new(),
            client_handler,
            Arc::new(|_, _, _| {}),
        )
        .unwrap();
        let mut client = TcpStream::connect(listener.local_addr()).unwrap();
        started_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        client
            .write_all(b"GET http://welcome/ HTTP/1.1\r\nHost: welcome\r\n")
            .unwrap();

        listener.stop();

        assert!(finished_rx.recv_timeout(TEST_TIMEOUT).unwrap());
        assert_eq!(listener.active_clients(), 0);
    }

    #[test]
    fn external_cancellation_wakes_the_accept_loop_and_reaps_workers() {
        let cancellation = CancellationToken::new();
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let client_handler: Arc<ClientHandler> = Arc::new(move |mut stream, _, _| {
            started_tx.send(()).unwrap();
            let mut byte = [0_u8; 1];
            let _result = stream.read(&mut byte);
            finished_tx.send(()).unwrap();
        });
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            cancellation.clone(),
            client_handler,
            Arc::new(|_, _, _| {}),
        )
        .unwrap();

        let _client = TcpStream::connect(listener.local_addr()).unwrap();
        started_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        cancellation.cancel();

        finished_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        wait_for_active_count(&listener, 0);
        listener.stop();
        assert_eq!(listener.active_clients(), 0);
    }

    #[test]
    fn stop_unblocks_a_stalled_rejection_handler() {
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let rejection_handler: Arc<RejectionHandler> = Arc::new(move |mut stream, _, _| {
            started_tx.send(()).unwrap();
            let mut byte = [0_u8; 1];
            let _result = stream.read(&mut byte);
            finished_tx.send(()).unwrap();
        });
        let listener = OwnedListener::start(
            loopback_any(),
            0,
            CancellationToken::new(),
            Arc::new(|_, _, _| {}),
            rejection_handler,
        )
        .unwrap();
        let _client = TcpStream::connect(listener.local_addr()).unwrap();
        started_rx.recv_timeout(TEST_TIMEOUT).unwrap();

        listener.stop();

        finished_rx.recv_timeout(TEST_TIMEOUT).unwrap();
    }

    #[test]
    fn client_handler_can_request_stop_without_a_join_cycle() {
        let owner = Arc::new(Mutex::new(None::<std::sync::Weak<OwnedListener>>));
        let callback_owner = Arc::clone(&owner);
        let (returned_tx, returned_rx) = mpsc::channel();
        let client_handler: Arc<ClientHandler> = Arc::new(move |_, _, _| {
            let listener = callback_owner
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .upgrade()
                .unwrap();
            listener.stop();
            returned_tx.send(()).unwrap();
        });
        let listener = Arc::new(
            OwnedListener::start(
                loopback_any(),
                1,
                CancellationToken::new(),
                client_handler,
                Arc::new(|_, _, _| {}),
            )
            .unwrap(),
        );
        *owner.lock().unwrap() = Some(Arc::downgrade(&listener));

        let _client = TcpStream::connect(listener.local_addr()).unwrap();
        returned_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        listener.stop();
        assert_eq!(listener.active_clients(), 0);
    }

    #[test]
    fn rejection_handler_can_request_stop_without_self_joining() {
        let owner = Arc::new(Mutex::new(None::<std::sync::Weak<OwnedListener>>));
        let callback_owner = Arc::clone(&owner);
        let (returned_tx, returned_rx) = mpsc::channel();
        let rejection_handler: Arc<RejectionHandler> = Arc::new(move |_, _, _| {
            let listener = callback_owner
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .upgrade()
                .unwrap();
            listener.stop();
            returned_tx.send(()).unwrap();
        });
        let listener = Arc::new(
            OwnedListener::start(
                loopback_any(),
                0,
                CancellationToken::new(),
                Arc::new(|_, _, _| {}),
                rejection_handler,
            )
            .unwrap(),
        );
        *owner.lock().unwrap() = Some(Arc::downgrade(&listener));

        let _client = TcpStream::connect(listener.local_addr()).unwrap();
        returned_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        listener.stop();
    }

    #[test]
    fn panicking_client_handler_releases_its_permit_and_is_joined() {
        let calls = Arc::new(AtomicUsize::new(0));
        let handler_calls = Arc::clone(&calls);
        let client_handler: Arc<ClientHandler> = Arc::new(move |_, _, _| {
            handler_calls.fetch_add(1, Ordering::Release);
            panic!("forced client handler panic");
        });
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            CancellationToken::new(),
            client_handler,
            Arc::new(|_, _, _| {}),
        )
        .unwrap();

        let _first = TcpStream::connect(listener.local_addr()).unwrap();
        wait_for_calls(&calls, 1);
        wait_for_active_count(&listener, 0);
        let _second = TcpStream::connect(listener.local_addr()).unwrap();
        wait_for_calls(&calls, 2);
        wait_for_active_count(&listener, 0);

        listener.stop();
    }

    #[test]
    fn panicking_rejection_handler_does_not_kill_the_accept_loop() {
        let calls = Arc::new(AtomicUsize::new(0));
        let rejection_calls = Arc::clone(&calls);
        let rejection_handler: Arc<RejectionHandler> = Arc::new(move |_, _, _| {
            rejection_calls.fetch_add(1, Ordering::Release);
            panic!("forced rejection handler panic");
        });
        let listener = OwnedListener::start(
            loopback_any(),
            0,
            CancellationToken::new(),
            Arc::new(|_, _, _| {}),
            rejection_handler,
        )
        .unwrap();

        let _first = TcpStream::connect(listener.local_addr()).unwrap();
        wait_for_calls(&calls, 1);
        let _second = TcpStream::connect(listener.local_addr()).unwrap();
        wait_for_calls(&calls, 2);

        listener.stop();
    }

    #[test]
    fn idle_stop_wakes_the_nonblocking_accept_loop_promptly() {
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            CancellationToken::new(),
            Arc::new(|_, _, _| {}),
            Arc::new(|_, _, _| {}),
        )
        .unwrap();
        let started = std::time::Instant::now();

        listener.stop();

        assert!(started.elapsed() < Duration::from_secs(1));
    }

    #[test]
    fn drop_stops_and_joins_the_listener() {
        let cancellation = CancellationToken::new();
        let (started_tx, started_rx) = mpsc::channel();
        let (finished_tx, finished_rx) = mpsc::channel();
        let client_handler: Arc<ClientHandler> = Arc::new(move |mut stream, _, _| {
            started_tx.send(()).unwrap();
            let mut byte = [0_u8; 1];
            let _result = stream.read(&mut byte);
            finished_tx.send(()).unwrap();
        });
        let listener = OwnedListener::start(
            loopback_any(),
            1,
            cancellation.clone(),
            client_handler,
            Arc::new(|_, _, _| {}),
        )
        .unwrap();
        let _client = TcpStream::connect(listener.local_addr()).unwrap();
        started_rx.recv_timeout(TEST_TIMEOUT).unwrap();

        drop(listener);

        assert!(cancellation.is_cancelled());
        finished_rx.recv_timeout(TEST_TIMEOUT).unwrap();
    }

    #[test]
    fn bind_failure_does_not_cancel_or_start_handlers() {
        let occupied = TcpListener::bind(loopback_any()).unwrap();
        let cancellation = CancellationToken::new();
        let calls = Arc::new(AtomicUsize::new(0));
        let handler_calls = Arc::clone(&calls);
        let client_handler: Arc<ClientHandler> = Arc::new(move |_, _, _| {
            handler_calls.fetch_add(1, Ordering::Relaxed);
        });
        let reject_calls = Arc::clone(&calls);
        let rejection_handler: Arc<RejectionHandler> = Arc::new(move |_, _, _| {
            reject_calls.fetch_add(1, Ordering::Relaxed);
        });

        let result = OwnedListener::start(
            occupied.local_addr().unwrap(),
            1,
            cancellation.clone(),
            client_handler,
            rejection_handler,
        );

        assert!(result.is_err());
        assert!(!cancellation.is_cancelled());
        assert_eq!(calls.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn spawn_failure_drops_the_bound_socket_without_starting_work() {
        let probe = TcpListener::bind(loopback_any()).unwrap();
        let address = probe.local_addr().unwrap();
        drop(probe);
        let cancellation = CancellationToken::new();

        let result = OwnedListener::start_with_spawner(
            address,
            1,
            cancellation.clone(),
            Arc::new(|_, _, _| {}),
            Arc::new(|_, _, _| {}),
            |_builder, _task| Err(io::Error::other("forced spawn failure")),
        );

        assert!(result.is_err());
        assert!(!cancellation.is_cancelled());
        let rebound = TcpListener::bind(address).unwrap();
        assert_eq!(rebound.local_addr().unwrap(), address);
    }

    #[test]
    fn refuses_non_loopback_bind_addresses() {
        let result = OwnedListener::start(
            SocketAddr::from(([0, 0, 0, 0], 0)),
            1,
            CancellationToken::new(),
            Arc::new(|_, _, _| {}),
            Arc::new(|_, _, _| {}),
        );

        assert!(result.is_err());
    }

    #[test]
    fn refuses_an_already_cancelled_token_without_binding() {
        let cancellation = CancellationToken::new();
        cancellation.cancel();

        let result = OwnedListener::start(
            loopback_any(),
            1,
            cancellation,
            Arc::new(|_, _, _| {}),
            Arc::new(|_, _, _| {}),
        );

        assert!(result.is_err());
    }

    #[test]
    fn aborted_and_reset_accepts_are_retryable_but_fatal_errors_are_not() {
        assert!(is_transient_accept_error(&io::Error::from(
            io::ErrorKind::Interrupted
        )));
        assert!(is_transient_accept_error(&io::Error::from(
            io::ErrorKind::ConnectionAborted
        )));
        assert!(is_transient_accept_error(&io::Error::from(
            io::ErrorKind::ConnectionReset
        )));
        assert!(!is_transient_accept_error(&io::Error::from(
            io::ErrorKind::AddrNotAvailable
        )));
    }

    fn loopback_any() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::LOCALHOST, 0))
    }

    fn wait_for_active_count(listener: &OwnedListener, expected: usize) {
        let deadline = std::time::Instant::now() + TEST_TIMEOUT;
        while listener.active_clients() != expected {
            assert!(std::time::Instant::now() < deadline);
            thread::sleep(Duration::from_millis(5));
        }
    }

    fn wait_for_calls(calls: &AtomicUsize, expected: usize) {
        let deadline = std::time::Instant::now() + TEST_TIMEOUT;
        while calls.load(Ordering::Acquire) < expected {
            assert!(std::time::Instant::now() < deadline);
            thread::sleep(Duration::from_millis(5));
        }
    }
}
