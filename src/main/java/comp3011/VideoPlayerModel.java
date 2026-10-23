/// 
 //  Starter code supplied for Adelaide University COMP3011 Assignment 2.
 //  Students are free to modify this file for assessment purposes.
 //  
 //  Authors:
 //    1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 //    2. <student name and student number insert here upon modification>
 // 
 //  Copyright 2026 Simon Ratcliffe
 // /
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

// Extra concurrent collections are needed because frame processing
// is no longer all happening on one thread. BlockingQueue is used to safely pass
// frames between processing stages, while ConcurrentLinkedQueue is used for audio
// because the decoder adds audio and the JavaFX thread removes it.
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/// // 
 //  The model part of the video player model-view-controller architecture.
 // 
 //  <p>
 //  Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 //  and frame processing. For Part 2 I changed the original single-threaded frame
 //  processing into a small pipeline so different frames can be worked on at the
 //  same time by different CPU cores, while each frame still receives effects in
 //  the exact command-line order.
 // 
 //  For the more complicated concurrency design I used AI to help compare a few
 //  possible approaches. It suggested a staged producer/consumer pipeline, which
 //  seemed like the best fit because it improves parallelism without changing the
 //  visible behaviour of the player or the order of the frame effects.
 // 
 //  Playback state is still sent back through callbacks supplied by the owning
 //  {@link VideoPlayerController}, keeping this model separate from the JavaFX view.
 //  </p>
 // /
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    // Only a few frames are allowed to wait in each stage.
    // This stops the decoder from running too far ahead and using lots of memory
    // if one of the effects is slower than the others.
    private static final int PIPELINE_QUEUE_CAPACITY = 2;
    private static final int PREPARED_FRAME_BUFFER_CAPACITY = 4;

    private final List<FrameProcessor> frameProcessors;

    // Audio is now added by the decoder thread but played from the
    // JavaFX heartbeat. The original ArrayDeque was not safe for two threads, so
    // this was changed to a thread-safe queue to avoid both threads changing it
    // at the same time.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();

    // Finished frames are stored here until it is actually time to
    // display them. This means the expensive effects can run in the background
    // instead of making the JavaFX/UI thread do all the work.
    private final BlockingQueue<PreparedFrame> preparedFrames =
            new ArrayBlockingQueue<>(PREPARED_FRAME_BUFFER_CAPACITY);

    // Each frame effect becomes one stage in the pipeline. A single
    // frame still moves through the effects in the same order the user typed them,
    // but different frames can be in different stages at the same time. This is
    // where most of the multi-core performance improvement should come from.
    private final List<BlockingQueue<FrameJob>> pipelineQueues = new ArrayList<>();
    private final List<Thread> pipelineThreads = new ArrayList<>();

    // A seek can happen while old frames are still being processed.
    // I use a generation number like a version number for the current playback.
    // AI helped suggest this approach because simply clearing the queues is not
    // enough when a worker is already processing an old frame. Old generations
    // can then be detected and dropped before they reach the screen.
    private final AtomicLong playbackGeneration = new AtomicLong();

    // This object is used to put the decoder thread to sleep when it
    // has nothing useful to do, such as while paused. It can then be woken for a
    // seek, resume or stop instead of constantly looping and wasting CPU.
    private final Object decoderSignal = new Object();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    // Only the decoder thread is allowed to use FFmpegFrameGrabber.
    // This keeps grabbing and seeking in one place so two threads cannot modify
    // the same decoder state at the same time.
    private Thread decoderThread;

    // These values are checked by both the JavaFX thread and the
    // background workers. volatile is used so a pause/stop/seek change made by one
    // thread becomes visible to the others without locking the whole playback loop.
    private volatile boolean playbackOpen; // True when playback is happening.
    private volatile boolean pauseRequested;
    private volatile boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private volatile boolean pipelineDrained;
    private volatile boolean decodeSingleFrameWhilePaused;
    private volatile boolean pausedSeekPreviewRequested;
    private volatile long pendingSeekUs = NO_SEEK_REQUEST;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private volatile long firstTimestampUs = NO_SEEK_REQUEST;
    private volatile long logicalPlaybackBaseUs;
    private volatile long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = new ArrayList<>(frameProcessors);
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        signalDecoder();
        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        // When seeking, frames already being processed belong to the
        // old position. Moving to a new generation marks those old frames/audio as
        // stale so they can be ignored if they finish after the seek.
        playbackGeneration.incrementAndGet();
        clearPipelineBuffers();
        resetPlaybackClock(logicalTimestampUs);
        flushAudioOutput();
        pipelineDrained = false;

        // The decoder is the only thread allowed to reposition FFmpeg. Passing
        // the seek request to it avoids racing setTimestamp() against grab().
        synchronized (decoderSignal) {
            pendingSeekUs = grabTimestampUs;
            decodeSingleFrameWhilePaused = pauseRequested;
            pausedSeekPreviewRequested = pauseRequested;
            decoderSignal.notifyAll();
        }

        notifyPlaybackStateChanged();
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackGeneration.incrementAndGet();
            pipelineDrained = false;
            pendingSeekUs = NO_SEEK_REQUEST;
            decodeSingleFrameWhilePaused = false;
            pausedSeekPreviewRequested = false;
            playbackOpen = true;

            //   Start the worker pipeline before the JavaFX timer.
            // The timer now mostly displays frames that are already prepared, rather
            // than doing decoding and all frame effects itself.
            startPipelineWorkers();
            playbackTimer.start();
            signalDecoder();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrames.clear();
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

     //  Starts a bounded producer/consumer pipeline. Decoding has one owner thread,
     //  every FrameProcessor has one dedicated stage thread, and conversion to a
     //  JavaFX Image happens in a final stage. Because each processor instance has
     //  exactly one owner thread, its mutable scratch state remains safe while
     //  different frames can be processed by different stages at the same time.
    private void startPipelineWorkers() {
        pipelineQueues.clear();
        pipelineThreads.clear();

        // There is one queue before the first processor, one between each pair
        // of processors, and one feeding the image-conversion stage.
        for (int i = 0; i <= frameProcessors.size(); i++) {
            pipelineQueues.add(new ArrayBlockingQueue<>(PIPELINE_QUEUE_CAPACITY));
        }

        for (int i = 0; i < frameProcessors.size(); i++) {
            int stageIndex = i;
            FrameProcessor processor = frameProcessors.get(i);
            Thread stageThread = new Thread(
                    () -> runProcessorStage(
                            processor,
                            pipelineQueues.get(stageIndex),
                            pipelineQueues.get(stageIndex + 1)),
                    "frame-processor-" + (i + 1) + "-" + processor.getClass().getSimpleName());
            stageThread.setDaemon(true);
            pipelineThreads.add(stageThread);
        }

        BlockingQueue<FrameJob> finalQueue = pipelineQueues.get(pipelineQueues.size() - 1);
        Thread conversionThread = new Thread(
                () -> runConversionStage(finalQueue),
                "frame-image-converter");
        conversionThread.setDaemon(true);
        pipelineThreads.add(conversionThread);

        decoderThread = new Thread(
                () -> runDecoder(pipelineQueues.get(0)),
                "video-decoder");
        decoderThread.setDaemon(true);
        pipelineThreads.add(decoderThread);

        // Consumers start first so the decoder can immediately hand work off.
        for (Thread thread : pipelineThreads) {
            if (thread != decoderThread) {
                thread.start();
            }
        }
        decoderThread.start();
    }

     //    This is the producer at the start of the pipeline. It reads
     //  frames from FFmpeg and passes video frames to the first processing stage.
     //  I kept decoding on one thread because the same FFmpegFrameGrabber is also
     //  used for seeking. The frame effects are the expensive part that is spread
     //  across the other worker threads.
    private void runDecoder(BlockingQueue<FrameJob> output) {
        boolean decoderAtEnd = false;

        try {
            while (playbackOpen && !Thread.currentThread().isInterrupted()) {
                waitUntilDecoderCanRun(decoderAtEnd);
                if (!playbackOpen) {
                    return;
                }

                long seekTimestampUs = takePendingSeekRequest();
                if (seekTimestampUs != NO_SEEK_REQUEST) {
                    grabber.setTimestamp(seekTimestampUs);
                    decoderAtEnd = false;
                }

                // A paused seek is allowed to decode one video frame so that the
                // user can see the new position without resuming playback.
                if (pauseRequested && !decodeSingleFrameWhilePaused) {
                    continue;
                }

                long generation = playbackGeneration.get();
                Frame frame = grabFrame();

                // A seek may have occurred while FFmpeg was inside grab().
                if (generation != playbackGeneration.get()) {
                    continue;
                }

                if (frame == null) {
                    output.put(FrameJob.endOfStream(generation));
                    decoderAtEnd = true;
                    continue;
                }

                long timestampUs = grabber.getTimestamp();
                int frameNumber = grabber.getFrameNumber();

                if (audioAvailable && frame.samples != null) {
                    queueAudio(timestampUs, frame, generation);
                }

                if (frame.image == null) {
                    continue;
                }

                if (!frameProcessorsInitialised) {
                    initialiseFrameProcessors(new InfoVideo(
                            mediaName(videoFile),
                            totalVideoFrames,
                            frame.imageWidth,
                            frame.imageHeight,
                            frame.imageDepth,
                            frame.imageChannels,
                            frame.imageStride,
                            frameRate,
                            intFrameRate,
                            videoFrameDurationUs,
                            grabber.getPixelFormat()));
                    frameProcessorsInitialised = true;
                }

                if (firstTimestampUs == NO_SEEK_REQUEST) {
                    firstTimestampUs = timestampUs;
                    playbackStartNs = System.nanoTime();
                    if (pauseRequested) {
                        pauseStartedNs = playbackStartNs;
                    }
                }

                long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
                long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
                InfoFrame info = new InfoFrame(frameNumber, timestampUs);

                //   This frame is cloned before being sent to another
                // thread. AI helped point out that FFmpegFrameGrabber may reuse the
                // memory from a previous grab. That was fine in the original serial
                // version, but now the decoder can grab the next frame while this one
                // is still being processed, so it needs its own copy.
                Frame pipelineFrame = frame.clone();

                if (generation == playbackGeneration.get()) {
                    output.put(new FrameJob(
                            pipelineFrame,
                            info,
                            frameNumber,
                            timestampUs,
                            relativeTimestampUs,
                            logicalTimestampUs,
                            generation,
                            false));
                }

                if (pauseRequested && decodeSingleFrameWhilePaused) {
                    decodeSingleFrameWhilePaused = false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            reportPlaybackError(e, playbackGeneration.get());
        }
    }


     //  Makes the decoder wait when playback is paused or has reached
     //  the end. It is woken when the user resumes, seeks or stops. This avoids a
     //  loop that would otherwise keep checking the same condition and waste CPU.
    private void waitUntilDecoderCanRun(boolean decoderAtEnd) throws InterruptedException {
        synchronized (decoderSignal) {
            while (playbackOpen
                    && ((pauseRequested && !decodeSingleFrameWhilePaused && pendingSeekUs == NO_SEEK_REQUEST)
                            || (decoderAtEnd && pendingSeekUs == NO_SEEK_REQUEST))) {
                decoderSignal.wait();
            }
        }
    }

    private long takePendingSeekRequest() {
        synchronized (decoderSignal) {
            long requestedTimestampUs = pendingSeekUs;
            pendingSeekUs = NO_SEEK_REQUEST;
            return requestedTimestampUs;
        }
    }

     //  Each FrameProcessor has one worker thread. This allows, for
     //  example, one effect to process frame 11 while the next effect processes
     //  frame 10. A single frame still goes through the stages one-by-one, so the
     //  order and repeated effects from Feature 1 stay the same.
     // 
     //  AI helped suggest keeping one owner thread per processor instead of running
     //  the same processor object on many threads. Some supplied effects keep their
     //  own temporary state, so this approach seemed safer while still using multiple
     //  cores across different processing stages.
    private void runProcessorStage(
            FrameProcessor processor,
            BlockingQueue<FrameJob> input,
            BlockingQueue<FrameJob> output) {
        try {
            while (playbackOpen && !Thread.currentThread().isInterrupted()) {
                FrameJob job = input.take();

                if (job.endOfStream()) {
                    if (job.generation() == playbackGeneration.get()) {
                        output.put(job);
                    }
                    continue;
                }

                if (job.generation() != playbackGeneration.get()) {
                    continue;
                }

                try {
                    processor.process(job.frame(), job.info());
                } catch (Exception e) {
                    reportPlaybackError(e, job.generation());
                    return;
                }

                // Discard work that became stale while the effect was running.
                if (job.generation() == playbackGeneration.get()) {
                    output.put(job);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

     //  This is the last background stage. Once all effects are done,
     //  the JavaCV Frame is converted to a JavaFX Image here instead of on the UI
     //  thread. The finished image can then wait until its correct display time.
    private void runConversionStage(BlockingQueue<FrameJob> input) {
        try {
            while (playbackOpen && !Thread.currentThread().isInterrupted()) {
                FrameJob job = input.take();

                if (job.endOfStream()) {
                    if (job.generation() == playbackGeneration.get()) {
                        pipelineDrained = true;
                    }
                    continue;
                }

                if (job.generation() != playbackGeneration.get()) {
                    continue;
                }

                Image image;
                try {
                    image = converter.convert(job.frame());
                } catch (Exception e) {
                    reportPlaybackError(e, job.generation());
                    return;
                }

                PreparedFrame prepared = new PreparedFrame(
                        image,
                        job.frameNumber(),
                        job.mediaTimestampUs(),
                        job.relativeTimestampUs(),
                        job.logicalTimestampUs(),
                        System.nanoTime(),
                        job.generation());

                preparedFrames.put(prepared);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

     //  Background workers should not directly update JavaFX state.
     //  Platform.runLater sends the error handling back to the JavaFX thread. The
     //  generation check also stops an old frame from reporting an error after the
     //  user has already seeked or restarted playback.
    private void reportPlaybackError(Exception error, long generation) {
        Platform.runLater(() -> {
            if (playbackOpen && generation == playbackGeneration.get()) {
                handlePlaybackError(error);
            }
        });
    }

    //   Small helper used whenever the decoder needs to be woken up,
    // for example after resume, seek or stop.
    private void signalDecoder() {
        synchronized (decoderSignal) {
            decoderSignal.notifyAll();
        }
    }

    // Clear frames/audio that are still waiting in queues after a
    // seek or restart. A frame that is already inside a worker cannot be cleared
    // here, which is why the generation check is also needed.
    private void clearPipelineBuffers() {
        for (BlockingQueue<FrameJob> queue : pipelineQueues) {
            queue.clear();
        }
        preparedFrames.clear();
        pendingAudio.clear();
    }

    // The starter version prepared each frame during the JavaFX
    // heartbeat, so heavy effects could delay both playback and UI work. The main
    // processing is now done by background workers. This method mostly checks the
    // clock, plays due audio and displays a frame when its target time is reached.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);

        PreparedFrame frame = nextCurrentPreparedFrame();
        if (frame != null) {
            if (pauseRequested) {
                if (pausedSeekPreviewRequested) {
                    preparedFrames.poll();
                    pausedSeekPreviewRequested = false;
                    displayPreparedFrame(frame, now);
                }
            } else {
                long targetTimeNs = playbackStartNs + frame.relativeTimestampUs() //  1_000L;
                if (playbackStartNs > 0 && targetTimeNs <= now) {
                    preparedFrames.poll();
                    displayPreparedFrame(frame, now);
                }
            }
        }

        // End playback only after the EOF marker has travelled through every
        // processing stage and all prepared frames have reached the display.
        if (!pauseRequested && pipelineDrained && nextCurrentPreparedFrame() == null) {
            finishPlayback();
        }
    }

    private PreparedFrame nextCurrentPreparedFrame() {
        long generation = playbackGeneration.get();
        PreparedFrame frame = preparedFrames.peek();
        while (frame != null && frame.generation() != generation) {
            preparedFrames.poll();
            frame = preparedFrames.peek();
        }
        return frame;
    }

    private void displayPreparedFrame(PreparedFrame frame, long now) {
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Compute the target from the current playback clock rather than baking
        // it into the frame. This keeps already-buffered frames correctly timed
        // across pause/resume clock adjustments.
        long targetTimeNs = playbackStartNs + frame.relativeTimestampUs() //  1_000L;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    // Audio also stores the playback generation. This keeps it in
    // sync with the video because audio decoded before a seek can be discarded too.
    private void queueAudio(long timestampUs, Frame frame, long generation) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples, generation));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.generation() != playbackGeneration.get()) {
                pendingAudio.remove();
                continue;
            }
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;

        // Mark current jobs as old before shutting everything down.
        // The worker threads are stopped before the decoder/converter are closed so
        // a background thread cannot keep using a resource after it has been freed.
        playbackGeneration.incrementAndGet();
        signalDecoder();
        stopPipelineWorkers();
        clearPipelineBuffers();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        pendingSeekUs = NO_SEEK_REQUEST;
        pipelineDrained = false;
        decodeSingleFrameWhilePaused = false;
        pausedSeekPreviewRequested = false;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

     //    Stops all pipeline workers when playback is closed/restarted.
     //  interrupt() releases a worker that may be blocked waiting on a queue, and
     //  join() gives it a chance to finish before the media resources are closed.
    private void stopPipelineWorkers() {
        for (Thread thread : pipelineThreads) {
            thread.interrupt();
        }
        signalDecoder();

        Thread currentThread = Thread.currentThread();
        for (Thread thread : pipelineThreads) {
            if (thread == currentThread) {
                continue;
            }
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        pipelineThreads.clear();
        pipelineQueues.clear();
        decoderThread = null;
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

     //  Small data object passed from one pipeline stage to the next.
     //  It keeps the frame together with its timing and generation information, so
     //  workers know which frame they are handling and whether it is still current.
     //  endOfStream is also passed through the same pipeline to show that decoding
     //  has finished.
    private record FrameJob(
            Frame frame,
            InfoFrame info,
            int frameNumber,
            long mediaTimestampUs,
            long relativeTimestampUs,
            long logicalTimestampUs,
            long generation,
            boolean endOfStream) {

        static FrameJob endOfStream(long generation) {
            return new FrameJob(null, null, 0, 0, 0, 0, generation, true);
        }
    }


     //  Represents a frame after all effects and image conversion are
     //  finished. preparedAtNs is kept so displayPreparedFrame() can still calculate
     //  the assignment's frame-headroom value and show whether processing is keeping
     //  up with real time.

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long relativeTimestampUs,
            long logicalTimestampUs,
            long preparedAtNs,
            long generation) {
    }
 
     //  Stores an audio packet while it waits for its playback time.
     //  The generation number is included for the same reason as video frames: old
     //  audio from before a seek should not be played afterwards.
    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private final long generation;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples, long generation) {
            this.timestampUs = timestampUs;
            this.samples = samples;
            this.generation = generation;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        long generation() {
            return generation;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
