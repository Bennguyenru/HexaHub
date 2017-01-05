package com.defold.editor;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLContext;

import com.defold.util.Profiler;
import com.defold.util.Profiler.Sample;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncCopier {

    private static final int N_BUFFERS = 2;
    private List<Buffer> buffers = new ArrayList<>();
    private int pboIndex = 0;

    private int width;
    private int height;
    private int pendingWidth;
    private int pendingHeight;
    private boolean pendingSizeChange;
    
    private ExecutorService threadPool;
    private ImageView imageView;

    private BlockingQueue<WritableImage> freeImages = new ArrayBlockingQueue<>(N_BUFFERS);
    private ArrayList<WritableImage> readyImages = new ArrayList<>();
    private Task<Void> task;

    private static Logger logger = LoggerFactory.getLogger(AsyncCopier.class);

    private static class Buffer {
        int pbo;

        Buffer(int pbo) {
            this.pbo = pbo;
        }

        void setSize(GL2 gl, int width, int height) {
            long data_size = width * height * 4;
            gl.glBindBuffer(GL2.GL_PIXEL_PACK_BUFFER, pbo);
            gl.glBufferData(GL2.GL_PIXEL_PACK_BUFFER, data_size, null, GL2.GL_STREAM_READ);
            gl.glBindBuffer(GL2.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    public AsyncCopier(GL2 gl, ExecutorService threadPool, ImageView imageView, int width, int height) {
        this.threadPool = threadPool;
        this.imageView = imageView;
        setPendingSize(width, height);
        IntBuffer pboBuffers = IntBuffer.allocate(N_BUFFERS);
        gl.glGenBuffers(N_BUFFERS, pboBuffers);
        int[] pbos = pboBuffers.array();
        for (int i = 0; i < N_BUFFERS; ++i) {
            Buffer b = new Buffer(pbos[i]);
            buffers.add(b);
            freeImages.add(new WritableImage(width, height));
        }
        realizeSize(gl);
    }

    private void displayImage(int frame) {
        synchronized (readyImages) {
            if (!readyImages.isEmpty()) {

                Sample setImage = Profiler.begin("render-async", "set-image", frame);
                Image oldImage = imageView.getImage();
                if (oldImage != null) {
                    try {
                        freeImages.put((WritableImage) oldImage);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                imageView.setImage(readyImages.remove(readyImages.size() - 1));
                Profiler.end(setImage);
            }

            for (WritableImage image : readyImages) {
                try {
                    freeImages.put(image);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            readyImages.clear();
        }
    }

    public boolean tryBeginFrame(GL2 gl) {
        Sample begin = Profiler.begin("render-async", "begin");

        if (task != null) {
            try {
                Sample wait = Profiler.begin("render-async", "wait");
                task.get();
                task = null;
                Profiler.end(wait);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        boolean success = gl.getContext().makeCurrent() != GLContext.CONTEXT_NOT_CURRENT;

        Profiler.end(begin);

        if (success) {
            realizeSize(gl);        
        }
        else {
            logger.warn("makeCurrent() failed in beginFrame");
        }

        return success;
    }

    public void endFrame(GL2 gl) {
        final Sample end = Profiler.begin("render-async", "end-frame");

        pboIndex = (pboIndex + 1) % N_BUFFERS;
        Buffer readTo = buffers.get(pboIndex);
        gl.glBindBuffer(GL2.GL_PIXEL_PACK_BUFFER, readTo.pbo);
        int type = GL2.GL_UNSIGNED_INT_8_8_8_8_REV;
        Sample readPixels = Profiler.begin("render-async", "read-pixels");
        gl.glReadPixels(0, 0, width, height, GL2.GL_BGRA, type, 0);
        Profiler.end(readPixels);

        gl.getContext().release();

        if (task != null) {
            throw new RuntimeException("task in flight!");
        }

        task = new Task<Void>() {

            @Override
            protected Void call() throws Exception {
                if (gl.getContext().makeCurrent() != GLContext.CONTEXT_NOT_CURRENT) {
                    try {
                        gl.glBindBuffer(GL2.GL_PIXEL_PACK_BUFFER, readTo.pbo);
                        Sample mapBuffer = Profiler.begin("render-async", "map-buffer", end.getFrame());
                        ByteBuffer buffer = gl.glMapBuffer(GL2.GL_PIXEL_PACK_BUFFER, GL2.GL_READ_ONLY);
                        Profiler.end(mapBuffer);

                        PixelFormat<IntBuffer> pf = PixelFormat.getIntArgbPreInstance();
                        Sample setPixels = Profiler.begin("render-async", "set-pixels", end.getFrame());

                        WritableImage image = freeImages.poll(1, TimeUnit.MILLISECONDS);
                        if (image != null) {
                            if (image.getWidth() != width || image.getHeight() != height) {
                                image = new WritableImage(width, height);
                            }
                            image.getPixelWriter().setPixels(0, 0, width, height, pf, buffer.asIntBuffer(), width);
                        } else {
                            logger.warn("no image available"); // happens pretty frequently when resizing
                        }

                        Profiler.end(setPixels);

                        gl.glUnmapBuffer(GL2.GL_PIXEL_PACK_BUFFER);
                        gl.glBindBuffer(GL2.GL_PIXEL_PACK_BUFFER, 0);

                        synchronized (readyImages) {
                            if (image != null) {
                                readyImages.add(image);
                            }
                        }

                        if (image != null) {
                            Platform.runLater(new Runnable() {
                                    @Override
                                    public void run() {
                                        displayImage(end.getFrame());
                                    }
                                });
                        }

                    } finally {
                        gl.getContext().release();
                    }
                } else {
                    logger.warn("makeCurrent() failed in endFrame/task");
                }

                return null;
            }
        };
        threadPool.submit(task);
        Profiler.end(end);
    }

    public void setPendingSize(int width, int height) {
        pendingWidth = width;
        pendingHeight = height;
        pendingSizeChange = true;
    }

    private void realizeSize(GL2 gl) {
        if (pendingSizeChange) {
            Sample begin = Profiler.begin("render-async", "resize");
            for (Buffer buffer : buffers) {
                buffer.setSize(gl, pendingWidth, pendingHeight);
            }
            width = pendingWidth;
            height = pendingHeight;
            pendingSizeChange = false;
            Profiler.end(begin);
        }
    }

    public void dispose(GL2 gl) {
        int[] b = new int[buffers.size()];
        for (int i = 0; i < b.length; i++) {
            b[i] = buffers.get(i).pbo;
        }
        gl.glDeleteBuffers(b.length, b, 0);
    }

}
