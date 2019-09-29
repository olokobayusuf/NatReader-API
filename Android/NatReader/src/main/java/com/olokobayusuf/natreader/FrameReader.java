package com.olokobayusuf.natreader;

import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import com.olokobayusuf.natrender.GLBlitEncoder;
import com.olokobayusuf.natrender.GLRenderContext;
import com.olokobayusuf.natrender.Unmanaged;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class FrameReader implements MediaReader {

    //region --Client API--

    public interface Callback {
        void onFrame (ByteBuffer pixelBuffer, int width, int height, long timestamp);
    }

    public FrameReader(Callback callback) {
        this.callback = callback;
        this.callbackHandler = new Handler(Looper.myLooper());
        this.extractor = new MediaExtractor();
    }

    public void startReading (String url) {
        // Set extractor URL
        try {
            extractor.setDataSource(url);
        } catch (IOException ex) { return; }
        // Get the last video track index
        for (int i = 0; i < extractor.getTrackCount(); i++)
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                videoTrackIndex = i;
                break;
            }
        if (videoTrackIndex == -1) {
            Log.e("Unity", "NatReader Error: Failed to find video track in media at URL: " + url);
            return;
        }
        // Extract properties
        extractor.selectTrack(videoTrackIndex);
        final MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
        final int width = format.getInteger(MediaFormat.KEY_WIDTH);
        final int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        // Create frame reader
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4);
        renderContext = new GLRenderContext(null, imageReader.getSurface(), false);
        renderContext.start();
        renderContextHandler = new Handler(renderContext.getLooper());
        imageReader.setOnImageAvailableListener(imageReaderCallback, renderContextHandler);
        renderContextHandler.post(new Runnable() {
            @Override
            public void run () {
                // Create output texture
                decoderOutputTextureID = GLBlitEncoder.getExternalTexture();
                decoderOutputTexture = new SurfaceTexture(decoderOutputTextureID);
                decoderOutputTexture.setOnFrameAvailableListener(surfaceTextureCallback, renderContextHandler);
                decoderOutputSurface = new Surface(decoderOutputTexture);
                // Create decoder
                try {
                    final MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                    decoder.setCallback(decoderCallback, renderContextHandler);
                    decoder.configure(format, decoderOutputSurface, null, 0);
                    decoder.start();
                } catch (IOException ex) {
                    Log.e("Unity", "NatReader Error: Failed to start decoder with error: " +  ex);
                    decoderOutputSurface.release();
                    decoderOutputTexture.release();
                    GLBlitEncoder.releaseTexture(decoderOutputTextureID);
                    return;
                }
                // Create blit encoder
                blitEncoder = GLBlitEncoder.externalBlitEncoder();
            }
        });
    }

    public void release () {
        extractor.unselectTrack(videoTrackIndex);
        videoTrackIndex = -1;
    }
    //endregion


    //region --Operations--

    private final Callback callback;
    private final Handler callbackHandler;
    private final MediaExtractor extractor;
    private int videoTrackIndex = -1;
    private ImageReader imageReader;
    private ByteBuffer pixelBuffer;
    private GLRenderContext renderContext;
    private Handler renderContextHandler;
    private GLBlitEncoder blitEncoder;
    private int decoderOutputTextureID;
    private SurfaceTexture decoderOutputTexture;
    private Surface decoderOutputSurface;

    private final MediaCodec.Callback decoderCallback = new MediaCodec.Callback () {

        @Override
        public void onInputBufferAvailable (MediaCodec codec, int index) {
            final ByteBuffer inputBuffer = codec.getInputBuffer(index);
            final int dataSize = extractor.readSampleData(inputBuffer, 0);
            if (dataSize >= 0) {
                codec.queueInputBuffer(index, 0, dataSize, extractor.getSampleTime(), extractor.getSampleFlags());
                extractor.advance();
            } else
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }

        @Override
        public void onOutputBufferAvailable (MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            // Send to consumer
            codec.releaseOutputBuffer(index, true);
            // Check for EOS
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                extractor.unselectTrack(videoTrackIndex);
                extractor.release();
                codec.stop();
                codec.release();
                //decoderThread.quitSafely();
            }
        }

        @Override
        public void onError (MediaCodec codec, MediaCodec.CodecException e) {
            Log.e("Unity", "NatReader Error: Decoder encountered error: " + e);
        }

        @Override
        public void onOutputFormatChanged (MediaCodec codec, MediaFormat format) {
            Log.v("Unity", "NatReader: Decoder received media format: " + format);
        }
    };

    private final SurfaceTexture.OnFrameAvailableListener surfaceTextureCallback = new SurfaceTexture.OnFrameAvailableListener() {

        @Override
        public void onFrameAvailable (SurfaceTexture surfaceTexture) {
            // Update transform
            final float[] transform = new float[16];
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(transform);
            Matrix.translateM(transform, 0, 0.5f, 0.5f, 0.f);
            Matrix.scaleM(transform, 0, 1, -1, 1);
            Matrix.translateM(transform, 0, -0.5f, -0.5f, 0.f);
            // Blit
            blitEncoder.blit(decoderOutputTextureID, transform);
            renderContext.setPresentationTime(surfaceTexture.getTimestamp());
            renderContext.swapBuffers();
        }
    };

    private final ImageReader.OnImageAvailableListener imageReaderCallback = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable (ImageReader imageReader) {
            // Create contiguous buffer with no padding
            final Image image = imageReader.acquireLatestImage();
            if (image == null)
                return;
            final Image.Plane imagePlane = image.getPlanes()[0];
            final ByteBuffer sourceBuffer = imagePlane.getBuffer();
            final int width = image.getWidth();
            final int height = image.getHeight();
            final int stride = imagePlane.getRowStride();
            final long timestamp = image.getTimestamp();
            Unmanaged.copyFrame(Unmanaged.baseAddress(sourceBuffer), width, height, stride, Unmanaged.baseAddress(pixelBuffer));
            image.close();
            // Send to handler
            callbackHandler.post(new Runnable() {
                @Override
                public void run () {
                    callback.onFrame(pixelBuffer, width, height, timestamp);
                }
            });
        }
    };
    //endregion
}