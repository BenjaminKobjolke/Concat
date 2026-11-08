// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

//! Speech in and out, entirely on this machine.
//!
//! - [`transcribe`] - whisper.cpp, in-process, turns a clip's audio into
//!   timed caption segments.
//! - [`tts`] - Chatterbox on the desktop, and the phone's own voice on
//!   Android, turn typed narration into a WAV in the project folder.
//!
//! Both download their models on demand into the app's data directory and
//! never bundle them. Both are one-at-a-time: a [`concat_host::SingleFlight`]
//! refuses a second concurrent run rather than letting two share a cancel
//! flag.
//!
//! A separate crate from `concat-host` so the heavy native dependencies
//! (whisper.cpp, and ONNX Runtime for Chatterbox) stay out of everything
//! that does not speak.

#[cfg(feature = "chatterbox")]
pub mod chatterbox;
#[cfg(feature = "isolate")]
pub mod isolate;
pub mod transcribe;
pub mod tts;
#[cfg(feature = "isolate")]
pub mod vocals;

pub use transcribe::Transcriber;
pub use tts::Speech;

/// Pick a worker count from the machine's cores, memory and platform limits.
pub(crate) fn workers_for(memory: Option<u64>, worker_bytes: u64) -> usize {
    if let Some(asked) = std::env::var("CONCAT_ISOLATE_WORKERS")
        .ok()
        .and_then(|value| value.parse::<usize>().ok())
    {
        return asked.max(1);
    }
    let cores = std::thread::available_parallelism().map_or(2, |count| count.get());
    let most = if cfg!(any(target_os = "android", target_os = "ios")) {
        2
    } else {
        6
    };
    let by_memory = memory.map_or(2, |bytes| (bytes / 8 / worker_bytes) as usize);
    cores.saturating_sub(2).min(most).min(by_memory).max(1)
}

/// Open a speech pipeline's audio stream with the shared decode settings.
pub(crate) fn open_decoder(
    source: &std::path::Path,
    stream: Option<usize>,
    channels: usize,
    start: f64,
    duration: Option<f64>,
    rate: u32,
) -> Result<concat_media::samples::AudioDecoder, String> {
    use concat_media::samples::{AudioDecoder, AudioOptions, SampleFormat};

    AudioDecoder::open(
        source,
        &AudioOptions {
            start: (start > 0.0).then_some(start),
            duration,
            filters: Vec::new(),
            rate,
            channels: channels as u16,
            format: SampleFormat::F32,
            stream,
        },
    )
    .map_err(|error| error.to_string())
}

/// Whether the voices ask for the machine's own accelerator: CoreML, the
/// Mac's GPU and Neural Engine. Windows and Linux have none in the runtime
/// the app ships - Windows' is Microsoft's CPU build, and asking it for
/// DirectML crashed Chatterbox on its second network (#399). Not a setting. Measured on an M5 against
/// the CPU, a warm Chatterbox read took 48.6 s against 60.6. The one cost is Chatterbox's load, about
/// eleven seconds longer while CoreML compiles its networks, paid once per
/// run since the engine stays loaded. A model that will not load for CoreML
/// at all loads for the CPU instead (tts.rs).
pub const fn accelerated() -> bool {
    cfg!(target_os = "macos")
}

/// How far a transcription or a read has got, as it reports it: the stage
/// it is in, and in the work itself where it is and where the step under
/// way will leave it. A job is mostly long waits between reports - whisper
/// reports once per thirty seconds of sound, Chatterbox every few words - and
/// `next` is what lets a bar move through the wait without promising more
/// than the job has done.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum JobStage {
    /// Reading the sound in: a clip's, or the recording a voice is read
    /// from.
    Reading,
    /// Loading the model. Not reported when it is still loaded from the
    /// run before.
    Loading,
    /// The work itself.
    Working {
        /// How much of it is finished, `0..=1`.
        done: f32,
        /// Where the step under way ends, `done..=1`.
        next: f32,
    },
}

/// Progress for one model download.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DownloadProgress {
    /// Which model.
    pub id: String,
    /// Bytes received so far.
    pub received: u64,
    /// Content-Length when the server sent one, the table estimate otherwise.
    pub total: u64,
    /// True while an archive is being unpacked - bytes stop moving but the
    /// job is far from done, and the bar should say so.
    pub unpacking: bool,
    /// True on the final report.
    pub done: bool,
}

/// Streams a model into `partial` through the host's downloader:
/// Concat's own mirror first, the upstream it was filled from after it,
/// or the copy the person chose themselves, and the finished file checked
/// against the digest its table carries before the caller is told it
/// arrived. See [`concat_host::models::fetch`], which also says - when
/// every source fails - which hosts were tried and why they likely failed.
///
/// `file` is what the model is called on the mirror.
#[allow(clippy::too_many_arguments)]
pub(crate) fn fetch_model(
    file: &str,
    upstream: &str,
    partial: &std::path::Path,
    id: &str,
    estimate: u64,
    sha256: &str,
    cancel: &std::sync::atomic::AtomicBool,
    progress: &mut dyn FnMut(DownloadProgress),
) -> Result<(u64, u64), String> {
    concat_host::models::fetch(
        file,
        upstream,
        partial,
        estimate,
        sha256,
        cancel,
        "download cancelled",
        &mut |received, total| {
            progress(DownloadProgress {
                id: id.to_owned(),
                received,
                total,
                unpacking: false,
                done: false,
            })
        },
    )
}
