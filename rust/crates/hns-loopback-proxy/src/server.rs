//! Authenticated, scoped HTTP/1 loopback proxy lifecycle.

use crate::auth::ProxyAuthorization;
use crate::backend::{
    BackendError, CancellationToken, ProxyBackend, ProxyHeader, ProxyRequest, ProxyRequestBody,
    ProxyResponse, ProxyResponseBody,
};
use crate::config::{ProxyConfig, ProxyLimits, ProxyTimeouts};
use crate::endpoint::ProxyEndpoint;
use crate::event::{
    BackendFailureKind, ClientRejectionReason, LifecycleEvent, ObservedHost, ObservedMethod,
    ProxyEvent, ProxyObserver, RequestPhase, RequestRejectionReason, StopReason,
};
use crate::host::HostScopeError;
use crate::http1::{
    AbsoluteTarget, BodyFraming, Http1Error, RequestHead, RequestTarget, Scheme,
    determine_body_framing, read_request_body, read_request_head, sanitize_forward_headers,
};
use crate::listener::{ClientHandler, OwnedListener, RejectionHandler};
use crate::rate_limit::{
    RateLimitConfig, RateLimitConfigError, RateLimitDecision, RateLimitScope, RequestRateLimiter,
};
use crate::response::{encode_response_head, sanitize_response_headers};
use std::fmt;
use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use thiserror::Error;

const RESPONSE_COPY_BUFFER_BYTES: usize = 16 * 1024;

/// Failure to start a proxy generation. No listener or credential is retained
/// after this error is returned.
#[derive(Debug, Error)]
pub enum ProxyError {
    #[error("unable to generate mandatory loopback proxy credentials")]
    Authorization(#[source] crate::AuthorizationGenerationError),
    #[error("invalid loopback proxy rate-limit configuration")]
    RateLimit(#[source] RateLimitConfigError),
    #[error("unable to start the IPv4 loopback proxy listener")]
    Listener(#[source] io::Error),
}

/// One owned proxy generation and all of its listener/client work.
pub struct RunningProxy {
    endpoint: ProxyEndpoint,
    listener: OwnedListener,
    observer: Arc<dyn ProxyObserver>,
    generation: u64,
    stop_requested: AtomicBool,
    stop_completed: AtomicBool,
}

impl RunningProxy {
    /// Starts a fresh authenticated proxy on an operating-system-selected
    /// IPv4 loopback port.
    pub fn start(
        config: ProxyConfig,
        backend: Arc<dyn ProxyBackend>,
        observer: Arc<dyn ProxyObserver>,
    ) -> Result<Self, ProxyError> {
        let authorization =
            Arc::new(ProxyAuthorization::generate().map_err(ProxyError::Authorization)?);
        let limits = config.limits();
        let timeouts = config.timeouts();
        let generation = config.instance().generation();
        let rate_limiter =
            RequestRateLimiter::new(rate_limit_config(limits)).map_err(ProxyError::RateLimit)?;
        let cancellation = CancellationToken::new();
        let context = Arc::new(ServerContext {
            authorization: Arc::clone(&authorization),
            scope: config.scope().clone(),
            limits,
            timeouts,
            backend,
            observer: Arc::clone(&observer),
            generation,
            rate_limiter,
        });

        let client_context = Arc::clone(&context);
        let client_handler: Arc<ClientHandler> = Arc::new(move |stream, peer, token| {
            handle_client(stream, peer, token, &client_context);
        });
        let rejection_observer = Arc::clone(&observer);
        let rejection_handler: Arc<RejectionHandler> = Arc::new(move |mut stream, _, _| {
            let _result = stream.set_write_timeout(Some(timeouts.socket_timeout()));
            observe(
                rejection_observer.as_ref(),
                ProxyEvent::ClientRejected {
                    generation,
                    reason: ClientRejectionReason::ActiveClientLimit,
                },
            );
            let _result = write_error_response(
                &mut stream,
                429,
                "Too Many Requests",
                &[("Retry-After", "1".to_owned())],
            );
            let _result = stream.shutdown(Shutdown::Both);
        });

        let listener = OwnedListener::start(
            SocketAddr::V4(config.bind().socket_addr()),
            limits.max_active_clients(),
            cancellation,
            client_handler,
            rejection_handler,
        )
        .map_err(ProxyError::Listener)?;
        let endpoint = ProxyEndpoint::new(
            config.instance().clone(),
            listener.local_addr(),
            authorization,
        );
        observe(
            observer.as_ref(),
            ProxyEvent::Lifecycle {
                generation,
                event: LifecycleEvent::Listening {
                    port: endpoint.port(),
                },
            },
        );

        Ok(Self {
            endpoint,
            listener,
            observer,
            generation,
            stop_requested: AtomicBool::new(false),
            stop_completed: AtomicBool::new(false),
        })
    }

    pub fn endpoint(&self) -> &ProxyEndpoint {
        &self.endpoint
    }

    pub fn active_clients(&self) -> usize {
        self.listener.active_clients()
    }

    pub fn is_stopped(&self) -> bool {
        self.stop_completed.load(Ordering::Acquire)
    }

    /// Cancels socket/backend work and, from an external control thread, joins
    /// the listener and every client worker. Repeated and concurrent calls are
    /// safe. A reentrant observer callback requests cancellation; the next
    /// external call completes the join.
    pub fn stop(&self) {
        if !self.stop_requested.swap(true, Ordering::AcqRel) {
            observe(
                self.observer.as_ref(),
                ProxyEvent::Lifecycle {
                    generation: self.generation,
                    event: LifecycleEvent::Stopping,
                },
            );
        }
        let joined = self.listener.stop();
        if joined && !self.stop_completed.swap(true, Ordering::AcqRel) {
            observe(
                self.observer.as_ref(),
                ProxyEvent::Lifecycle {
                    generation: self.generation,
                    event: LifecycleEvent::Stopped {
                        reason: StopReason::Requested,
                    },
                },
            );
        }
    }
}

impl fmt::Debug for RunningProxy {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RunningProxy")
            .field("endpoint", &self.endpoint)
            .field("active_clients", &self.active_clients())
            .field(
                "stop_requested",
                &self.stop_requested.load(Ordering::Acquire),
            )
            .field("stop_completed", &self.is_stopped())
            .finish()
    }
}

impl Drop for RunningProxy {
    fn drop(&mut self) {
        self.stop();
    }
}

struct ServerContext {
    authorization: Arc<ProxyAuthorization>,
    scope: crate::HostScope,
    limits: ProxyLimits,
    timeouts: ProxyTimeouts,
    backend: Arc<dyn ProxyBackend>,
    observer: Arc<dyn ProxyObserver>,
    generation: u64,
    rate_limiter: RequestRateLimiter,
}

fn handle_client(
    mut stream: TcpStream,
    peer: SocketAddr,
    cancellation: CancellationToken,
    context: &ServerContext,
) {
    if !peer.ip().is_loopback() {
        observe_client_rejection(context, ClientRejectionReason::InvalidPeer);
        let _result = stream.shutdown(Shutdown::Both);
        return;
    }
    if cancellation.is_cancelled() {
        observe_client_rejection(context, ClientRejectionReason::Cancelled);
        let _result = stream.shutdown(Shutdown::Both);
        return;
    }

    let _result = stream.set_nodelay(true);
    if stream
        .set_write_timeout(Some(context.timeouts.socket_timeout()))
        .is_err()
    {
        observe_client_rejection(context, ClientRejectionReason::InvalidRequest);
        return;
    }

    let head = {
        let mut reader =
            DeadlineReader::new(&mut stream, context.timeouts.request_header_timeout());
        match read_request_head(&mut reader, context.limits.max_header_bytes()) {
            Ok(head) => head,
            Err(error) => {
                observe_client_rejection(context, ClientRejectionReason::InvalidRequest);
                let (status, reason) = request_error_status(&error);
                let _result = write_error_response(&mut stream, status, reason, &[]);
                return;
            }
        }
    };

    // Authentication is evaluated before the request target is interpreted,
    // classified, scoped, rate-limited, or sent to the backend.
    if !context
        .authorization
        .verify_header_values(head.header_values(crate::PROXY_AUTHORIZATION_HEADER))
    {
        observe_client_rejection(context, ClientRejectionReason::AuthenticationRequired);
        let _result = write_error_response(
            &mut stream,
            407,
            "Proxy Authentication Required",
            &[(
                crate::PROXY_AUTHENTICATE_HEADER,
                context.authorization.challenge_header_value(),
            )],
        );
        return;
    }

    let target = match head.validated_target(None) {
        Ok(target) => target,
        Err(error) => {
            observe_client_rejection(context, ClientRejectionReason::InvalidRequest);
            let _result =
                write_error_response(&mut stream, error.status_code(), error.reason_phrase(), &[]);
            return;
        }
    };
    let method = ObservedMethod::from_token(head.method());
    let canonical_host = match context.scope.authorize(target.authority().host()) {
        Ok(host) => host,
        Err(error) => {
            let (status_reason, rejection) = scope_rejection(error);
            if let Ok(host) = crate::NormalizedHost::parse(target.authority().host()) {
                observe_request(
                    context,
                    &host,
                    method,
                    RequestPhase::Rejected { reason: rejection },
                );
            }
            let _result = write_error_response(&mut stream, 403, status_reason, &[]);
            return;
        }
    };

    observe_request(context, &canonical_host, method, RequestPhase::Accepted);

    if matches!(&target, RequestTarget::Connect(_)) {
        reject_scoped_request(
            &mut stream,
            context,
            &canonical_host,
            method,
            501,
            "HNS HTTPS Termination Unavailable",
            RequestRejectionReason::InvalidRequest,
        );
        return;
    }
    let RequestTarget::Absolute(absolute) = target else {
        reject_scoped_request(
            &mut stream,
            context,
            &canonical_host,
            method,
            400,
            "Bad Request",
            RequestRejectionReason::InvalidRequest,
        );
        return;
    };
    if matches!(absolute.scheme(), Scheme::Ws | Scheme::Wss) || requests_upgrade(&head) {
        reject_scoped_request(
            &mut stream,
            context,
            &canonical_host,
            method,
            501,
            "HNS Protocol Upgrade Unsupported",
            RequestRejectionReason::InvalidRequest,
        );
        return;
    }

    match context
        .rate_limiter
        .check(canonical_host.as_str(), Instant::now())
    {
        RateLimitDecision::Allowed => {}
        RateLimitDecision::Limited { scope, retry_after } => {
            let reason = match scope {
                RateLimitScope::Global => RequestRejectionReason::GlobalRateLimit,
                RateLimitScope::Host => RequestRejectionReason::HostRateLimit,
            };
            observe_request(
                context,
                &canonical_host,
                method,
                RequestPhase::Rejected { reason },
            );
            let _result = write_error_response(
                &mut stream,
                429,
                "Too Many Requests",
                &[("Retry-After", retry_after_seconds(retry_after).to_string())],
            );
            return;
        }
    }

    let framing = match determine_body_framing(head.headers()) {
        Ok(framing) => framing,
        Err(error) => {
            reject_http_request(&mut stream, context, &canonical_host, method, &error);
            return;
        }
    };
    if matches!(
        framing,
        BodyFraming::ContentLength(length) if length > context.limits.max_body_bytes()
    ) {
        reject_http_request(
            &mut stream,
            context,
            &canonical_host,
            method,
            &Http1Error::BodyTooLarge,
        );
        return;
    }
    let expects_continue = match expects_continue(&head) {
        Ok(expects) => expects,
        Err(()) => {
            reject_scoped_request(
                &mut stream,
                context,
                &canonical_host,
                method,
                417,
                "Expectation Failed",
                RequestRejectionReason::InvalidRequest,
            );
            return;
        }
    };
    let forward_headers = match build_forward_headers(&head, &absolute, &canonical_host) {
        Ok(headers) => headers,
        Err(error) => {
            reject_http_request(&mut stream, context, &canonical_host, method, &error);
            return;
        }
    };

    if expects_continue
        && !matches!(framing, BodyFraming::None)
        && stream.write_all(b"HTTP/1.1 100 Continue\r\n\r\n").is_err()
    {
        return;
    }
    let body = {
        let mut reader = DeadlineReader::new(&mut stream, context.timeouts.socket_timeout());
        match read_request_body(&mut reader, framing, context.limits.max_body_bytes()) {
            Ok(bytes) => bytes,
            Err(error) => {
                reject_http_request(&mut stream, context, &canonical_host, method, &error);
                return;
            }
        }
    };
    if cancellation.is_cancelled() {
        observe_request(
            context,
            &canonical_host,
            method,
            RequestPhase::Rejected {
                reason: RequestRejectionReason::Cancelled,
            },
        );
        return;
    }

    let request = ProxyRequest {
        method: head.method().to_owned(),
        scheme: absolute.scheme().as_str().to_owned(),
        host: canonical_host.as_str().to_owned(),
        port: absolute.authority().port(),
        path_and_query: absolute.path_and_query().to_owned(),
        headers: forward_headers,
        body: if body.is_empty() {
            ProxyRequestBody::Empty
        } else {
            ProxyRequestBody::Bytes(body)
        },
    };
    execute_backend(
        &mut stream,
        context,
        &canonical_host,
        method,
        head.method(),
        request,
        &cancellation,
    );
}

#[allow(clippy::too_many_arguments)]
fn execute_backend(
    stream: &mut TcpStream,
    context: &ServerContext,
    host: &crate::NormalizedHost,
    method: ObservedMethod,
    request_method: &str,
    request: ProxyRequest,
    cancellation: &CancellationToken,
) {
    let started = Instant::now();
    let response = catch_unwind(AssertUnwindSafe(|| {
        context.backend.execute(request, cancellation)
    }))
    .unwrap_or(Err(BackendError::Internal));
    let response = match response {
        Ok(response) => response,
        Err(error) => {
            observe_request(
                context,
                host,
                method,
                RequestPhase::BackendFailed {
                    kind: backend_failure_kind(error),
                    elapsed: started.elapsed(),
                },
            );
            if !cancellation.is_cancelled() {
                let (status, reason) = backend_error_status(error);
                let _result = write_error_response(stream, status, reason, &[]);
            }
            return;
        }
    };
    if cancellation.is_cancelled() {
        return;
    }
    let status = response.head.status_code;
    match write_backend_response(stream, request_method, response, cancellation) {
        Ok(()) => observe_request(
            context,
            host,
            method,
            RequestPhase::Completed {
                status_code: status,
                elapsed: started.elapsed(),
            },
        ),
        Err(WriteBackendError::InvalidBeforeHead) => {
            observe_invalid_response(context, host, method, started.elapsed());
            if !cancellation.is_cancelled() {
                let _result = write_error_response(stream, 502, "Invalid Upstream Response", &[]);
            }
        }
        Err(WriteBackendError::InvalidAfterHead) => {
            observe_invalid_response(context, host, method, started.elapsed());
        }
        Err(WriteBackendError::Io) => {}
    }
}

fn observe_invalid_response(
    context: &ServerContext,
    host: &crate::NormalizedHost,
    method: ObservedMethod,
    elapsed: Duration,
) {
    observe_request(
        context,
        host,
        method,
        RequestPhase::BackendFailed {
            kind: BackendFailureKind::InvalidResponse,
            elapsed,
        },
    );
}

fn write_backend_response(
    stream: &mut TcpStream,
    request_method: &str,
    response: ProxyResponse,
    cancellation: &CancellationToken,
) -> Result<(), WriteBackendError> {
    let ProxyResponse { head, body } = response;
    let header_pairs: Vec<_> = head
        .headers
        .into_iter()
        .map(|header| (header.name, header.value))
        .collect();
    let headers = sanitize_response_headers(&header_pairs)
        .map_err(|_error| WriteBackendError::InvalidBeforeHead)?;
    let body_len = body.expected_len();
    let encoded = encode_response_head(
        request_method,
        head.status_code,
        &head.reason_phrase,
        &headers,
        body_len,
    )
    .map_err(|_error| WriteBackendError::InvalidBeforeHead)?;
    let body_allowed = encoded.body_allowed();
    stream
        .write_all(encoded.as_bytes())
        .map_err(|_error| WriteBackendError::Io)?;
    if !body_allowed {
        return Ok(());
    }
    write_response_body(stream, body, cancellation)
}

fn write_response_body(
    stream: &mut TcpStream,
    body: ProxyResponseBody,
    cancellation: &CancellationToken,
) -> Result<(), WriteBackendError> {
    match body {
        ProxyResponseBody::Bytes(bytes) => {
            if cancellation.is_cancelled() {
                return Err(WriteBackendError::Io);
            }
            stream
                .write_all(&bytes)
                .map_err(|_error| WriteBackendError::Io)
        }
        ProxyResponseBody::Stream {
            expected_len,
            mut reader,
        } => copy_exact_response(stream, reader.as_mut(), expected_len, cancellation),
    }
}

fn copy_exact_response(
    stream: &mut TcpStream,
    reader: &mut dyn Read,
    mut remaining: u64,
    cancellation: &CancellationToken,
) -> Result<(), WriteBackendError> {
    let mut buffer = [0_u8; RESPONSE_COPY_BUFFER_BYTES];
    while remaining != 0 {
        if cancellation.is_cancelled() {
            return Err(WriteBackendError::Io);
        }
        let limit = usize::try_from(remaining.min(buffer.len() as u64))
            .map_err(|_error| WriteBackendError::InvalidAfterHead)?;
        let count = catch_unwind(AssertUnwindSafe(|| reader.read(&mut buffer[..limit])))
            .map_err(|_panic| WriteBackendError::InvalidAfterHead)?
            .map_err(|_error| WriteBackendError::InvalidAfterHead)?;
        if count == 0 || count > limit {
            return Err(WriteBackendError::InvalidAfterHead);
        }
        stream
            .write_all(&buffer[..count])
            .map_err(|_error| WriteBackendError::Io)?;
        remaining = remaining
            .checked_sub(count as u64)
            .ok_or(WriteBackendError::InvalidAfterHead)?;
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum WriteBackendError {
    InvalidBeforeHead,
    InvalidAfterHead,
    Io,
}

fn build_forward_headers(
    head: &RequestHead,
    target: &AbsoluteTarget,
    host: &crate::NormalizedHost,
) -> Result<Vec<ProxyHeader>, Http1Error> {
    let mut headers: Vec<_> = sanitize_forward_headers(head.headers())?
        .into_iter()
        .filter(|header| {
            !header.name().eq_ignore_ascii_case("host")
                && !header.name().eq_ignore_ascii_case("content-length")
                && !header.name().eq_ignore_ascii_case("expect")
        })
        .map(|header| ProxyHeader::new(header.name(), header.value()))
        .collect();
    let default_port = target.scheme().default_port();
    let host_value = if target.authority().port() == default_port {
        host.as_str().to_owned()
    } else {
        format!("{}:{}", host.as_str(), target.authority().port())
    };
    headers.push(ProxyHeader::new("Host", host_value));
    Ok(headers)
}

fn expects_continue(head: &RequestHead) -> Result<bool, ()> {
    let mut values = head.header_values("expect");
    let Some(first) = values.next() else {
        return Ok(false);
    };
    if values.next().is_some() || !first.eq_ignore_ascii_case("100-continue") {
        return Err(());
    }
    Ok(true)
}

fn requests_upgrade(head: &RequestHead) -> bool {
    head.header_values("upgrade").next().is_some()
        || head
            .header_values("connection")
            .flat_map(|value| value.split(','))
            .any(|token| token.trim().eq_ignore_ascii_case("upgrade"))
}

fn reject_http_request(
    stream: &mut TcpStream,
    context: &ServerContext,
    host: &crate::NormalizedHost,
    method: ObservedMethod,
    error: &Http1Error,
) {
    let rejection = if matches!(error, Http1Error::BodyTooLarge) {
        RequestRejectionReason::RequestTooLarge
    } else {
        RequestRejectionReason::InvalidRequest
    };
    let (status, status_reason) = request_error_status(error);
    reject_scoped_request(
        stream,
        context,
        host,
        method,
        status,
        status_reason,
        rejection,
    );
}

#[allow(clippy::too_many_arguments)]
fn reject_scoped_request(
    stream: &mut TcpStream,
    context: &ServerContext,
    host: &crate::NormalizedHost,
    method: ObservedMethod,
    status: u16,
    reason: &'static str,
    rejection: RequestRejectionReason,
) {
    observe_request(
        context,
        host,
        method,
        RequestPhase::Rejected { reason: rejection },
    );
    let _result = write_error_response(stream, status, reason, &[]);
}

fn scope_rejection(error: HostScopeError) -> (&'static str, RequestRejectionReason) {
    match error {
        HostScopeError::OutOfScope => (
            "HNS Proxy Scope Denied",
            RequestRejectionReason::HostOutsideScope,
        ),
        HostScopeError::NotHns | HostScopeError::InvalidHost(_) => (
            "Proxy Scope Denied",
            RequestRejectionReason::HostOutsideScope,
        ),
    }
}

fn request_error_status(error: &Http1Error) -> (u16, &'static str) {
    match error {
        Http1Error::Io(source)
            if matches!(
                source.kind(),
                io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
            ) =>
        {
            (408, "Request Timeout")
        }
        _ => (error.status_code(), error.reason_phrase()),
    }
}

fn backend_error_status(error: BackendError) -> (u16, &'static str) {
    match error {
        BackendError::Cancelled => (503, "Proxy Request Cancelled"),
        BackendError::InvalidRequest => (400, "Invalid Gateway Request"),
        BackendError::PolicyDenied => (403, "Gateway Policy Denied"),
        BackendError::ResolutionFailed => (502, "HNS Resolution Failed"),
        BackendError::TlsValidationFailed => (502, "HNS TLS Validation Failed"),
        BackendError::UpstreamUnavailable => (502, "HNS Upstream Unavailable"),
        BackendError::InvalidResponse => (502, "Invalid Upstream Response"),
        BackendError::ResponseTooLarge => (502, "Upstream Response Too Large"),
        BackendError::Internal => (500, "Proxy Internal Error"),
    }
}

fn backend_failure_kind(error: BackendError) -> BackendFailureKind {
    match error {
        BackendError::Cancelled => BackendFailureKind::Cancelled,
        BackendError::InvalidRequest => BackendFailureKind::InvalidRequest,
        BackendError::PolicyDenied => BackendFailureKind::PolicyDenied,
        BackendError::ResolutionFailed => BackendFailureKind::Resolution,
        BackendError::TlsValidationFailed => BackendFailureKind::TlsValidation,
        BackendError::UpstreamUnavailable => BackendFailureKind::Upstream,
        BackendError::InvalidResponse => BackendFailureKind::InvalidResponse,
        BackendError::ResponseTooLarge => BackendFailureKind::ResponseTooLarge,
        BackendError::Internal => BackendFailureKind::Internal,
    }
}

fn rate_limit_config(limits: ProxyLimits) -> RateLimitConfig {
    RateLimitConfig {
        global_requests: limits.max_requests_per_window(),
        per_host_requests: limits.max_requests_per_host_per_window(),
        window: limits.rate_window(),
        max_tracked_hosts: limits.max_tracked_hosts(),
    }
}

fn retry_after_seconds(duration: Duration) -> u64 {
    duration.as_secs() + u64::from(duration.subsec_nanos() != 0)
}

fn write_error_response(
    stream: &mut TcpStream,
    status: u16,
    reason: &'static str,
    extra_headers: &[(&'static str, String)],
) -> io::Result<()> {
    let body = reason.as_bytes();
    let mut response = format!(
        "HTTP/1.1 {status} {reason}\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Type: text/plain; charset=utf-8\r\nX-Content-Type-Options: nosniff\r\n"
    )
    .into_bytes();
    for (name, value) in extra_headers {
        response.extend_from_slice(name.as_bytes());
        response.extend_from_slice(b": ");
        response.extend_from_slice(value.as_bytes());
        response.extend_from_slice(b"\r\n");
    }
    response.extend_from_slice(format!("Content-Length: {}\r\n\r\n", body.len()).as_bytes());
    response.extend_from_slice(body);
    stream.write_all(&response)
}

struct DeadlineReader<'a> {
    stream: &'a mut TcpStream,
    deadline: Instant,
}

impl<'a> DeadlineReader<'a> {
    fn new(stream: &'a mut TcpStream, timeout: Duration) -> Self {
        let now = Instant::now();
        let deadline = now.checked_add(timeout).unwrap_or(now);
        Self { stream, deadline }
    }
}

impl Read for DeadlineReader<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let remaining = self
            .deadline
            .checked_duration_since(Instant::now())
            .filter(|remaining| !remaining.is_zero())
            .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "request deadline elapsed"))?;
        self.stream.set_read_timeout(Some(remaining))?;
        self.stream.read(buffer)
    }
}

fn observe_client_rejection(context: &ServerContext, reason: ClientRejectionReason) {
    observe(
        context.observer.as_ref(),
        ProxyEvent::ClientRejected {
            generation: context.generation,
            reason,
        },
    );
}

fn observe_request(
    context: &ServerContext,
    host: &crate::NormalizedHost,
    method: ObservedMethod,
    phase: RequestPhase,
) {
    let Ok(host) = ObservedHost::new(host.as_str()) else {
        return;
    };
    observe(
        context.observer.as_ref(),
        ProxyEvent::Request {
            generation: context.generation,
            host,
            method,
            phase,
        },
    );
}

fn observe(observer: &dyn ProxyObserver, event: ProxyEvent) {
    let _result = catch_unwind(AssertUnwindSafe(|| observer.observe(&event)));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{NoopProxyObserver, ProxyInstanceId, ProxyResponseHead, ProxySessionId};
    use std::io::Cursor;
    use std::sync::{Mutex, mpsc};
    use std::thread;

    const TEST_TIMEOUT: Duration = Duration::from_secs(2);

    fn test_config() -> ProxyConfig {
        ProxyConfig::new(
            ProxyInstanceId::new(ProxySessionId::generate().unwrap(), 1),
            crate::HostScope::new("welcome").unwrap(),
        )
    }

    struct UnusedBackend;

    impl ProxyBackend for UnusedBackend {
        fn execute(
            &self,
            _request: ProxyRequest,
            _cancellation: &CancellationToken,
        ) -> Result<ProxyResponse, BackendError> {
            Err(BackendError::Internal)
        }
    }

    #[derive(Clone)]
    enum ResponsePlan {
        Fixed {
            headers: Vec<ProxyHeader>,
            body: Vec<u8>,
        },
        ShortStream {
            expected_len: u64,
            bytes: Vec<u8>,
        },
    }

    impl ResponsePlan {
        fn plain(body: impl Into<Vec<u8>>) -> Self {
            Self::Fixed {
                headers: vec![ProxyHeader::new("Content-Type", "text/plain")],
                body: body.into(),
            }
        }

        fn response(&self) -> ProxyResponse {
            let (headers, body) = match self {
                Self::Fixed { headers, body } => {
                    (headers.clone(), ProxyResponseBody::Bytes(body.clone()))
                }
                Self::ShortStream {
                    expected_len,
                    bytes,
                } => (
                    vec![ProxyHeader::new("Content-Type", "application/octet-stream")],
                    ProxyResponseBody::Stream {
                        expected_len: *expected_len,
                        reader: Box::new(Cursor::new(bytes.clone())),
                    },
                ),
            };
            ProxyResponse {
                head: ProxyResponseHead {
                    status_code: 200,
                    reason_phrase: "OK".to_owned(),
                    headers,
                },
                body,
            }
        }
    }

    struct RecordingBackend {
        requests: Mutex<Vec<ProxyRequest>>,
        response: ResponsePlan,
    }

    impl RecordingBackend {
        fn new(response: ResponsePlan) -> Self {
            Self {
                requests: Mutex::new(Vec::new()),
                response,
            }
        }

        fn request_count(&self) -> usize {
            self.requests
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .len()
        }

        fn take_requests(&self) -> Vec<ProxyRequest> {
            std::mem::take(
                &mut *self
                    .requests
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner()),
            )
        }
    }

    impl ProxyBackend for RecordingBackend {
        fn execute(
            &self,
            request: ProxyRequest,
            _cancellation: &CancellationToken,
        ) -> Result<ProxyResponse, BackendError> {
            self.requests
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .push(request);
            Ok(self.response.response())
        }
    }

    struct AcceptedObserver(mpsc::Sender<()>);

    impl ProxyObserver for AcceptedObserver {
        fn observe(&self, event: &ProxyEvent) {
            if matches!(
                event,
                ProxyEvent::Request {
                    phase: RequestPhase::Accepted,
                    ..
                }
            ) {
                let _result = self.0.send(());
            }
        }
    }

    fn start_recording_proxy(backend: Arc<RecordingBackend>) -> RunningProxy {
        RunningProxy::start(test_config(), backend, Arc::new(NoopProxyObserver)).unwrap()
    }

    fn send_raw(proxy: &RunningProxy, request: &[u8]) -> Vec<u8> {
        let mut stream = TcpStream::connect(proxy.endpoint().address()).unwrap();
        stream.set_read_timeout(Some(TEST_TIMEOUT)).unwrap();
        stream.set_write_timeout(Some(TEST_TIMEOUT)).unwrap();
        stream.write_all(request).unwrap();
        stream.shutdown(Shutdown::Write).unwrap();
        let mut response = Vec::new();
        stream.read_to_end(&mut response).unwrap();
        response
    }

    fn response_status(response: &[u8]) -> u16 {
        let line_end = response
            .windows(2)
            .position(|pair| pair == b"\r\n")
            .expect("response status line ends");
        std::str::from_utf8(&response[..line_end])
            .unwrap()
            .split_whitespace()
            .nth(1)
            .unwrap()
            .parse()
            .unwrap()
    }

    fn response_parts(response: &[u8]) -> (&str, &[u8]) {
        let head_end = response
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .expect("response head terminates");
        (
            std::str::from_utf8(&response[..head_end + 4]).unwrap(),
            &response[head_end + 4..],
        )
    }

    fn auth_header(proxy: &RunningProxy) -> String {
        format!(
            "Proxy-Authorization: {}\r\n",
            proxy.endpoint().authorization_header_value()
        )
    }

    fn wait_for_active_clients(proxy: &RunningProxy, expected: usize) {
        let deadline = Instant::now() + TEST_TIMEOUT;
        while proxy.active_clients() != expected && Instant::now() < deadline {
            thread::yield_now();
        }
        assert_eq!(proxy.active_clients(), expected);
    }

    fn assert_connection_closed(mut stream: TcpStream) {
        stream.set_read_timeout(Some(TEST_TIMEOUT)).unwrap();
        let mut byte = [0_u8; 1];
        match stream.read(&mut byte) {
            Ok(0) => {}
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::ConnectionReset
                        | io::ErrorKind::NotConnected
                        | io::ErrorKind::UnexpectedEof
                ) => {}
            result => panic!("expected a closed proxy connection, got {result:?}"),
        }
    }

    #[test]
    fn starts_on_ephemeral_loopback_with_fresh_redacted_credentials() {
        let first = RunningProxy::start(
            test_config(),
            Arc::new(UnusedBackend),
            Arc::new(NoopProxyObserver),
        )
        .unwrap();
        let second = RunningProxy::start(
            test_config(),
            Arc::new(UnusedBackend),
            Arc::new(NoopProxyObserver),
        )
        .unwrap();

        assert!(first.endpoint().address().ip().is_loopback());
        assert_ne!(first.endpoint().port(), 0);
        assert_ne!(first.endpoint().realm(), second.endpoint().realm());
        assert_ne!(first.endpoint().password(), second.endpoint().password());
        let debug = format!("{first:?}");
        assert!(!debug.contains(first.endpoint().realm()));
        assert!(!debug.contains(first.endpoint().password()));
        first.stop();
        first.stop();
        second.stop();
        assert!(first.is_stopped());
    }

    #[test]
    fn authentication_precedes_target_validation_and_rejects_duplicates() {
        let backend = Arc::new(RecordingBackend::new(ResponsePlan::plain(b"unused")));
        let proxy = start_recording_proxy(Arc::clone(&backend));
        let valid = proxy.endpoint().authorization_header_value();
        let cases = [
            "GET not-an-absolute-target HTTP/1.1\r\nHost: mismatch.example\r\n\r\n"
                .to_owned(),
            "GET not-an-absolute-target HTTP/1.1\r\nHost: mismatch.example\r\nProxy-Authorization: Basic d3Jvbmc6d3Jvbmc=\r\n\r\n"
                .to_owned(),
            format!(
                "GET not-an-absolute-target HTTP/1.1\r\nHost: mismatch.example\r\nProxy-Authorization: {valid}\r\nProxy-Authorization: {valid}\r\n\r\n"
            ),
            "CONNECT welcome:443 HTTP/1.1\r\nHost: welcome:443\r\n\r\n".to_owned(),
        ];

        for request in cases {
            let response = send_raw(&proxy, request.as_bytes());
            assert_eq!(response_status(&response), 407, "{request:?}");
            let (head, _) = response_parts(&response);
            assert!(head.contains(&format!(
                "Proxy-Authenticate: Basic realm=\"{}\"",
                proxy.endpoint().realm()
            )));
        }
        assert_eq!(backend.request_count(), 0);

        let request = format!(
            "GET not-an-absolute-target HTTP/1.1\r\nHost: welcome\r\n{}\r\n",
            auth_header(&proxy)
        );
        assert_eq!(response_status(&send_raw(&proxy, request.as_bytes())), 400);
        assert_eq!(backend.request_count(), 0);
        proxy.stop();
    }

    #[test]
    fn canonical_forwarding_and_both_header_boundaries_are_enforced() {
        let backend = Arc::new(RecordingBackend::new(ResponsePlan::Fixed {
            headers: vec![
                ProxyHeader::new("Content-Type", "text/plain"),
                ProxyHeader::new("X-Origin-Keep", "yes"),
                ProxyHeader::new("X-HNS-Security-Path", "dane"),
                ProxyHeader::new("X-HNS-Future", "private"),
                ProxyHeader::new("Connection", "X-Origin-Hop"),
                ProxyHeader::new("X-Origin-Hop", "remove"),
                ProxyHeader::new("Alt-Svc", "h3=\":443\""),
                ProxyHeader::new("Proxy-Authentication-Info", "secret"),
                ProxyHeader::new("Proxy-Future", "secret"),
                ProxyHeader::new("Content-Length", "999"),
                ProxyHeader::new("Transfer-Encoding", "chunked"),
            ],
            body: b"response-body".to_vec(),
        }));
        let proxy = start_recording_proxy(Arc::clone(&backend));
        let body = b"request-body";
        let request = format!(
            "POST http://Sub.WELCOME.:8080/private?q=s3cr3t HTTP/1.1\r\nHost: sub.welcome:8080\r\n{}Proxy-Future: secret\r\nX-HNS-Forged: secret\r\nConnection: keep-alive, X-Remove\r\nProxy-Connection: X-Proxy-Remove\r\nX-Remove: secret\r\nX-Proxy-Remove: secret\r\nKeep-Alive: timeout=5\r\nTE: trailers\r\nTrailer: X-Later\r\nAuthorization: Bearer origin-secret\r\nX-Keep: yes\r\nContent-Length: {}\r\n\r\n",
            auth_header(&proxy),
            body.len()
        );
        let mut bytes = request.into_bytes();
        bytes.extend_from_slice(body);

        let response = send_raw(&proxy, &bytes);
        assert_eq!(response_status(&response), 200);
        let (head, response_body) = response_parts(&response);
        assert_eq!(response_body, b"response-body");
        let lower_head = head.to_ascii_lowercase();
        assert!(lower_head.contains("x-origin-keep: yes\r\n"));
        assert!(lower_head.contains("connection: close\r\n"));
        assert!(lower_head.contains("content-length: 13\r\n"));
        for forbidden in [
            "x-hns-",
            "x-origin-hop",
            "alt-svc",
            "proxy-authentication-info",
            "proxy-future",
            "transfer-encoding",
            "content-length: 999",
        ] {
            assert!(
                !lower_head.contains(forbidden),
                "leaked {forbidden}: {head}"
            );
        }

        let requests = backend.take_requests();
        assert_eq!(requests.len(), 1);
        let forwarded = &requests[0];
        assert_eq!(forwarded.method, "POST");
        assert_eq!(forwarded.scheme, "http");
        assert_eq!(forwarded.host, "sub.welcome");
        assert_eq!(forwarded.port, 8080);
        assert_eq!(forwarded.path_and_query, "/private?q=s3cr3t");
        assert_eq!(forwarded.body.as_bytes(), body);
        let forwarded_names: Vec<_> = forwarded
            .headers
            .iter()
            .map(|header| header.name.to_ascii_lowercase())
            .collect();
        assert_eq!(
            forwarded_names
                .iter()
                .filter(|name| *name == "host")
                .count(),
            1
        );
        assert!(!forwarded_names.iter().any(|name| {
            name.starts_with("proxy-")
                || name.starts_with("x-hns-")
                || matches!(
                    name.as_str(),
                    "connection"
                        | "keep-alive"
                        | "te"
                        | "trailer"
                        | "transfer-encoding"
                        | "content-length"
                        | "x-remove"
                        | "x-proxy-remove"
                )
        }));
        assert!(forwarded.headers.iter().any(|header| {
            header.name.eq_ignore_ascii_case("host") && header.value == "sub.welcome:8080"
        }));
        assert!(forwarded.headers.iter().any(|header| {
            header.name.eq_ignore_ascii_case("authorization")
                && header.value == "Bearer origin-secret"
        }));
        assert!(
            forwarded.headers.iter().any(|header| {
                header.name.eq_ignore_ascii_case("x-keep") && header.value == "yes"
            })
        );
        proxy.stop();
    }

    #[test]
    fn fixed_and_chunked_request_bodies_are_forwarded_without_framing_fields() {
        let backend = Arc::new(RecordingBackend::new(ResponsePlan::plain(b"ok")));
        let proxy = start_recording_proxy(Arc::clone(&backend));
        let fixed = format!(
            "POST http://welcome/fixed HTTP/1.1\r\nHost: welcome\r\n{}Content-Length: 5\r\n\r\nfixed",
            auth_header(&proxy)
        );
        assert_eq!(response_status(&send_raw(&proxy, fixed.as_bytes())), 200);

        let chunked = format!(
            "POST http://welcome/chunked HTTP/1.1\r\nHost: welcome\r\n{}Transfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Origin-Trailer: ignored\r\n\r\n",
            auth_header(&proxy)
        );
        assert_eq!(response_status(&send_raw(&proxy, chunked.as_bytes())), 200);

        let requests = backend.take_requests();
        assert_eq!(requests.len(), 2);
        assert_eq!(requests[0].path_and_query, "/fixed");
        assert_eq!(requests[0].body.as_bytes(), b"fixed");
        assert_eq!(requests[1].path_and_query, "/chunked");
        assert_eq!(requests[1].body.as_bytes(), b"Wikipedia");
        for request in requests {
            assert!(!request.headers.iter().any(|header| {
                header.name.eq_ignore_ascii_case("content-length")
                    || header.name.eq_ignore_ascii_case("transfer-encoding")
                    || header.name.eq_ignore_ascii_case("x-hns-trailer")
                    || header.name.eq_ignore_ascii_case("x-origin-trailer")
            }));
        }
        proxy.stop();
    }

    #[test]
    fn oversized_expect_request_is_rejected_before_continue_or_body_read() {
        let backend = Arc::new(RecordingBackend::new(ResponsePlan::plain(b"unused")));
        let proxy = start_recording_proxy(Arc::clone(&backend));
        let request = format!(
            "POST http://welcome/upload HTTP/1.1\r\nHost: welcome\r\n{}Expect: 100-continue\r\nContent-Length: {}\r\n\r\n",
            auth_header(&proxy),
            ProxyLimits::DEFAULT_MAX_BODY_BYTES + 1,
        );

        let response = send_raw(&proxy, request.as_bytes());
        assert_eq!(response_status(&response), 413);
        assert!(!response.starts_with(b"HTTP/1.1 100 Continue"));
        assert_eq!(backend.request_count(), 0);
        proxy.stop();
    }

    #[test]
    fn connect_upgrade_and_out_of_scope_targets_never_reach_the_backend() {
        let backend = Arc::new(RecordingBackend::new(ResponsePlan::plain(b"unused")));
        let proxy = start_recording_proxy(Arc::clone(&backend));
        let auth = auth_header(&proxy);
        let cases = [
            (
                format!("CONNECT welcome:443 HTTP/1.1\r\nHost: welcome:443\r\n{auth}\r\n"),
                501,
            ),
            (
                format!(
                    "GET http://welcome/socket HTTP/1.1\r\nHost: welcome\r\n{auth}Connection: Upgrade\r\nUpgrade: websocket\r\n\r\n"
                ),
                501,
            ),
            (
                format!("GET ws://welcome/socket HTTP/1.1\r\nHost: welcome\r\n{auth}\r\n"),
                501,
            ),
            (
                format!("GET http://other/ HTTP/1.1\r\nHost: other\r\n{auth}\r\n"),
                403,
            ),
            (
                format!("GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n{auth}\r\n"),
                403,
            ),
        ];

        for (request, expected) in cases {
            assert_eq!(
                response_status(&send_raw(&proxy, request.as_bytes())),
                expected,
                "{request:?}"
            );
        }
        assert_eq!(backend.request_count(), 0);
        proxy.stop();
    }

    #[test]
    fn premature_response_stream_eof_closes_without_a_second_response() {
        let backend = Arc::new(RecordingBackend::new(ResponsePlan::ShortStream {
            expected_len: 9,
            bytes: b"tiny".to_vec(),
        }));
        let proxy = start_recording_proxy(Arc::clone(&backend));
        let request = format!(
            "GET http://welcome/stream HTTP/1.1\r\nHost: welcome\r\n{}\r\n",
            auth_header(&proxy)
        );

        let response = send_raw(&proxy, request.as_bytes());
        assert_eq!(response_status(&response), 200);
        let (head, body) = response_parts(&response);
        assert!(head.contains("Content-Length: 9\r\n"));
        assert_eq!(body, b"tiny");
        assert_eq!(
            response
                .windows(b"HTTP/1.1".len())
                .filter(|window| *window == b"HTTP/1.1")
                .count(),
            1
        );
        assert_eq!(backend.request_count(), 1);
        proxy.stop();
    }

    #[test]
    fn stop_closes_clients_stalled_in_partial_headers_and_bodies() {
        let header_backend = Arc::new(RecordingBackend::new(ResponsePlan::plain(b"unused")));
        let header_proxy = start_recording_proxy(Arc::clone(&header_backend));
        let mut partial_head = TcpStream::connect(header_proxy.endpoint().address()).unwrap();
        partial_head
            .write_all(b"GET http://welcome/ HTTP/1.1\r\nHost: welcome\r\n")
            .unwrap();
        wait_for_active_clients(&header_proxy, 1);
        header_proxy.stop();
        assert!(header_proxy.is_stopped());
        assert_eq!(header_proxy.active_clients(), 0);
        assert_eq!(header_backend.request_count(), 0);
        assert_connection_closed(partial_head);

        let body_backend = Arc::new(RecordingBackend::new(ResponsePlan::plain(b"unused")));
        let (accepted_tx, accepted_rx) = mpsc::channel();
        let body_proxy = RunningProxy::start(
            test_config(),
            body_backend.clone(),
            Arc::new(AcceptedObserver(accepted_tx)),
        )
        .unwrap();
        let mut partial_body = TcpStream::connect(body_proxy.endpoint().address()).unwrap();
        let request = format!(
            "POST http://welcome/upload HTTP/1.1\r\nHost: welcome\r\n{}Content-Length: 10\r\n\r\nabc",
            auth_header(&body_proxy)
        );
        partial_body.write_all(request.as_bytes()).unwrap();
        accepted_rx.recv_timeout(TEST_TIMEOUT).unwrap();
        wait_for_active_clients(&body_proxy, 1);
        body_proxy.stop();
        assert!(body_proxy.is_stopped());
        assert_eq!(body_proxy.active_clients(), 0);
        assert_eq!(body_backend.request_count(), 0);
        assert_connection_closed(partial_body);
    }

    #[test]
    fn helper_rounds_retry_after_up_and_maps_backend_failures() {
        assert_eq!(retry_after_seconds(Duration::from_nanos(1)), 1);
        assert_eq!(retry_after_seconds(Duration::from_secs(2)), 2);
        assert_eq!(
            backend_error_status(BackendError::TlsValidationFailed),
            (502, "HNS TLS Validation Failed")
        );
    }

    #[test]
    fn exact_stream_copy_rejects_premature_eof() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let client = std::thread::spawn(move || TcpStream::connect(address).unwrap());
        let (mut server, _) = listener.accept().unwrap();
        let _client = client.join().unwrap();

        assert_eq!(
            copy_exact_response(
                &mut server,
                &mut Cursor::new(b"x".to_vec()),
                2,
                &CancellationToken::new(),
            ),
            Err(WriteBackendError::InvalidAfterHead)
        );
    }
}
