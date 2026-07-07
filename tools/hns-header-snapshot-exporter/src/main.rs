use hns_chain::{HeaderChain, SqliteHeaderStore};
use hns_core::network;
use hns_core::{BlockHeader, HEADER_SIZE, Height};
use hns_p2p::{DnsSeedPeerSource, SqlitePeerStore};
use hns_sync::{
    HeaderSyncCoordinator, HeaderSyncRunner, HeaderSyncRunnerConfig, TcpHeaderPeerConnector,
};
use std::env;
use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const SNAPSHOT_MAGIC: &[u8] = b"HNSHDRSNAP1";
const DEFAULT_TARGET_HEIGHT: u32 = 300_000;
const DEFAULT_WORK_DIR: &str = "build/header-snapshot-work";

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse()?;
    fs::create_dir_all(&args.work_dir)?;

    let chain_path = args.work_dir.join("headers.sqlite");
    let peer_path = args.work_dir.join("peers.sqlite");
    let header_store = SqliteHeaderStore::open(chain_path)?;
    let mut chain = HeaderChain::new(header_store);
    if chain.best_header()?.is_none() {
        chain.insert_genesis(BlockHeader::mainnet_genesis())?;
    }
    let mut coordinator = HeaderSyncCoordinator::new(chain);

    let peer_store = SqlitePeerStore::open(peer_path)?;
    let mut peers = peer_store.load_manager()?;
    let network = network::mainnet();
    if peers.len() < 64 {
        let source = DnsSeedPeerSource::from_network(&network);
        let inserted = peers.seed_from(&source)?;
        if inserted > 0 {
            peer_store.save_manager(&peers)?;
        }
    }

    let runner = HeaderSyncRunner::with_config(
        network,
        TcpHeaderPeerConnector,
        HeaderSyncRunnerConfig {
            preferred_peers: 8,
            max_header_batches_per_peer: 16,
            discover_peers: false,
            peer_discovery_target: 0,
            peer_discovery_query_peers: 0,
            parallel_peer_probes: 0,
            parallel_header_fetch_peers: 1,
            peer_height_refresh_interval: 10 * 60,
            checkpoint_header_prefetch: Vec::new(),
            timeout: Duration::from_secs(10),
            ..HeaderSyncRunnerConfig::default()
        },
    );

    let mut no_progress_rounds = 0u32;
    loop {
        let best_height = coordinator
            .chain()
            .best_header()?
            .map(|header| header.height.0)
            .unwrap_or(0);
        if best_height >= args.target_height {
            break;
        }

        let result = runner.sync_once_parallel_and_persist(
            &mut coordinator,
            &mut peers,
            &peer_store,
            now_unix_seconds(),
        )?;
        let best_height = coordinator
            .chain()
            .best_header()?
            .map(|header| header.height.0)
            .unwrap_or(0);
        eprintln!(
            "best={} accepted={} attempted={} successful={} failed={} peers={}",
            best_height,
            result.accepted,
            result.attempted,
            result.successful,
            result.failures.len(),
            peers.len(),
        );
        for failure in result.failures.iter().take(5) {
            eprintln!(
                "  failure {} {} {}",
                failure.address,
                failure.stage.as_str(),
                failure.error
            );
        }
        if result.accepted == 0 && result.successful == 0 {
            no_progress_rounds = no_progress_rounds.saturating_add(1);
            if no_progress_rounds.is_multiple_of(3) {
                let source = DnsSeedPeerSource::from_network(&network::mainnet());
                let inserted = peers.seed_from(&source)?;
                if inserted > 0 {
                    peer_store.save_manager(&peers)?;
                }
                eprintln!(
                    "no progress for {} rounds; reseeded {} peers",
                    no_progress_rounds, inserted
                );
            }
            if no_progress_rounds >= 20 {
                return Err("sync made no progress after repeated retries".into());
            }
            thread::sleep(Duration::from_secs(3));
        } else {
            no_progress_rounds = 0;
        }
    }

    write_snapshot(coordinator.chain(), args.target_height, &args.output_path)?;
    Ok(())
}

fn write_snapshot(
    chain: &HeaderChain<SqliteHeaderStore>,
    target_height: u32,
    output_path: &Path,
) -> Result<(), Box<dyn std::error::Error>> {
    if let Some(parent) = output_path.parent() {
        fs::create_dir_all(parent)?;
    }

    let tip = chain
        .canonical_header(Height(target_height))
        .ok_or("target height is not available on the canonical chain")?;
    let mut output = File::create(output_path)?;
    output.write_all(SNAPSHOT_MAGIC)?;
    output.write_all(&target_height.to_be_bytes())?;
    output.write_all(&target_height.saturating_add(1).to_be_bytes())?;
    output.write_all(tip.hash.as_bytes())?;

    let mut written = 0u32;
    for height in 0..=target_height {
        let stored = chain
            .canonical_header(Height(height))
            .ok_or("canonical header is missing")?;
        let header = stored.header.serialize();
        if header.len() != HEADER_SIZE {
            return Err("serialized header length changed".into());
        }
        output.write_all(&header)?;
        written = written.saturating_add(1);
    }
    output.flush()?;
    eprintln!(
        "wrote {} headers through height {} to {}",
        written,
        target_height,
        output_path.display(),
    );
    eprintln!("tip hash {}", tip.hash);
    Ok(())
}

struct Args {
    output_path: PathBuf,
    target_height: u32,
    work_dir: PathBuf,
}

impl Args {
    fn parse() -> Result<Self, Box<dyn std::error::Error>> {
        let mut output_path = None;
        let mut target_height = DEFAULT_TARGET_HEIGHT;
        let mut work_dir = PathBuf::from(DEFAULT_WORK_DIR);
        let mut args = env::args().skip(1);

        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--target-height" => {
                    let value = args.next().ok_or("--target-height requires a value")?;
                    target_height = value.parse()?;
                }
                "--work-dir" => {
                    let value = args.next().ok_or("--work-dir requires a value")?;
                    work_dir = PathBuf::from(value);
                }
                "--help" | "-h" => {
                    print_usage()?;
                    std::process::exit(0);
                }
                value if value.starts_with('-') => {
                    return Err(format!("unknown option: {value}").into());
                }
                value => {
                    if output_path.is_some() {
                        return Err("only one output path is supported".into());
                    }
                    output_path = Some(PathBuf::from(value));
                }
            }
        }

        let output_path = output_path.ok_or("output path is required")?;
        Ok(Self {
            output_path,
            target_height,
            work_dir,
        })
    }
}

fn print_usage() -> io::Result<()> {
    writeln!(
        io::stderr(),
        "usage: hns-header-snapshot-exporter [--target-height N] [--work-dir DIR] OUTPUT"
    )
}

fn now_unix_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}
