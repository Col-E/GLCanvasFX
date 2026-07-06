package software.coley.glcanvasfx;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import jakarta.annotation.Nonnull;
import javafx.animation.AnimationTimer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import software.coley.bentofx.control.canvas.ArgbSource;
import software.coley.bentofx.control.canvas.PixelCanvas;
import software.coley.bentofx.control.canvas.PixelPainter;

import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.nio.ByteBuffer;

/**
 * A {@link PixelCanvas} wrapper that draws {@link GLAutoDrawable} contents.
 *
 * @author Matt Coley
 */
public class GLBentoCanvas extends BorderPane implements GLEventListener {
	/** Max width of any monitor in the current {@link GraphicsEnvironment} */
	private static final int MAX_WIDTH;
	/** Max height of any monitor in the current {@link GraphicsEnvironment} */
	private static final int MAX_HEIGHT;
	/** Bytes used for each BGRA pixel. */
	private static final int COLOR_BYTES = 4;

	static {
		int width = 1;
		int heigth = 1;
		for (GraphicsDevice screenDevice : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
			DisplayMode display = screenDevice.getDisplayMode();
			if (width < display.getWidth())
				width = display.getWidth();
			if (heigth < display.getHeight())
				heigth = display.getHeight();
		}
		MAX_WIDTH = width;
		MAX_HEIGHT = heigth;
	}

	/** Painter used by {@link #canvas} to commit the GL frame buffer directly to {@link PixelWriter}. */
	private final GLPixelPainter pixelPainter = new GLPixelPainter();
	/** Canvas to draw to. */
	private final AbsoluteSizedCanvas canvas = new AbsoluteSizedCanvas(pixelPainter);
	/** Timer to animate canvas draws on. */
	private final AnimationTimer canvasAnimation;
	/** Intermediate buffer to communicate between the {@link GLAutoDrawable#getGL() GL} frame buffer and {@link PixelWriter}. */
	private final ByteBuffer buffer;
	/** Width of the last image read into {@link #buffer}. */
	private int bufferImageWidth;
	/** Height of the last image read into {@link #buffer}. */
	private int bufferImageHeight;
	/** Current width of the GL display. */
	private int imageWidth;
	/** Current height of the GL display. */
	private int imageHeight;
	/** Incremented for each image committed to the canvas, used to bypass {@link PixelCanvas} draw de-duplication. */
	private long imageFrameId;
	/** Last millis timestamp of a resize event observed. Used to prevent visual tearing when updating the {@link #canvas} display. */
	private long lastResizeTimeMs;
	/** Flag indicating if {@link #display(GLAutoDrawable)} needs to update {@link #buffer}. Updated every FX render tick. */
	boolean fxAwaitingNewImage = true;
	/** Flag indicating if {@link #displayCanvas()} has new contents in {@link #buffer} to draw to the {@link #canvas}. */
	boolean imageBufferUpdated;

	/**
	 * Constructs a canvas that will draw the contents of the given drawable.
	 *
	 * @param drawable
	 * 		Drawable to pull graphics from.
	 */
	public GLBentoCanvas(@Nonnull GLAutoDrawable drawable) {
		// Buffer to hold MAX_WIDTH*MAX_HEIGHT pixels of BGRA data.
		//
		// We allocate a single array to the maximum possible size of the screen so that when
		// we observe the canvas changing size, we only have to update the buffer limit rather
		// than allocate a whole new buffer of the appropriate size. This saves loads of
		// memory when the user is fiddling around resizing things a lot & rapidly.
		byte[] bufferBacking = new byte[MAX_WIDTH * MAX_HEIGHT * COLOR_BYTES + COLOR_BYTES];
		buffer = ByteBuffer.wrap(bufferBacking);

		// Mark as managed so this doesn't prevent some containing SplitPane from shrinking.
		canvas.setManaged(false);

		// Interacting with the canvas should mark it as focused.
		canvas.setFocusTraversable(true);
		canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, _ -> canvas.requestFocus());

		// Add the canvas.
		setCenter(canvas);

		// Initialize initial dummy buffer state.
		refitBuffers(1, 1);

		// Register self as a GL listener so that we can transfer the GL frame buffer contents
		// into our own adapter buffer to be rendered later.
		drawable.addGLEventListener(this);

		// Register an FX animation timer that will draw the current adapted buffer contents
		// to the canvas every FX render pulse. This will only refresh the canvas when:
		//  - The canvas is present in some scene graph
		//  - The canvas is visible
		//  - The canvas is enabled
		canvasAnimation = new AnimationTimer() {
			@Override
			public void handle(long now) {
				displayCanvas();
			}
		};
		sceneProperty().addListener((_, _, _) -> refreshAnimationState());
		visibleProperty().addListener((_, _, _) -> refreshAnimationState());
		disabledProperty().addListener((_, _, _) -> refreshAnimationState());
	}

	private void refreshAnimationState() {
		if (getScene() != null && isVisible() && !isDisabled()) {
			canvasAnimation.start();
		} else {
			canvasAnimation.stop();
		}
	}

	@Override
	protected void layoutChildren() {
		super.layoutChildren();

		final double x = snappedLeftInset();
		final double y = snappedTopInset();
		final double w = snapSizeX(getWidth()) - x - snappedRightInset();
		final double h = snapSpaceX(getHeight()) - y - snappedBottomInset();

		// Ensure the canvas is always mapped to the space of our control.
		canvas.setLayoutX(x);
		canvas.setLayoutY(y);
		canvas.size(w, h);
	}

	@Override
	public void init(GLAutoDrawable drawable) {
		// no-op
	}

	@Override
	public void dispose(GLAutoDrawable drawable) {
		// no-op
	}

	@Override
	public void display(GLAutoDrawable drawable) {
		// Don't update the image buffer if the FX thread isn't ready for the next draw.
		if (!fxAwaitingNewImage)
			return;
		fxAwaitingNewImage = false;

		// Don't update the image if we recently resized.
		//
		// There is a single-frame where our buffer's "scanlineStride" may be incorrect for the new size
		// which can cause visual tearing & artifacts.
		long now = System.currentTimeMillis();
		if (now - lastResizeTimeMs < 20)
			return;

		// Read the pixel data from the GL context.
		buffer.position(0);
		int w = Math.max(1, Math.min(drawable.getSurfaceWidth(), imageWidth));
		int h = Math.max(1, Math.min(drawable.getSurfaceHeight(), imageHeight));
		drawable.getGL().glReadPixels(0, 0, w, h, GL.GL_BGRA, GL.GL_UNSIGNED_BYTE, buffer);
		bufferImageWidth = w;
		bufferImageHeight = h;

		// Notify UI next frame our buffer is updated and ready to redraw new contents.
		imageBufferUpdated = true;
	}

	@Override
	public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
		lastResizeTimeMs = System.currentTimeMillis();
		refitBuffers(width, height);
	}

	/**
	 * Updates our {@link #buffer} to fit the new GL viewport dimensions.
	 *
	 * @param width
	 * 		New viewport width.
	 * @param height
	 * 		New viewport height.
	 */
	private void refitBuffers(int width, int height) {
		imageWidth = Math.max(1, width);
		imageHeight = Math.max(1, height);
		buffer.limit(Math.max(buffer.limit(), imageWidth * imageHeight * COLOR_BYTES + COLOR_BYTES));
	}

	/**
	 * Called every FX animation pulse via the {@link AnimationTimer} managed in the constructor.
	 * <p/>
	 * Every frame we check if we have new contents in our {@link #buffer} assigned in {@link #display(GLAutoDrawable)}.
	 * If we do, we'll draw the contents on the canvas.
	 */
	private void displayCanvas() {
		// Don't update the canvas if our image hasn't been updated since our last draw.
		if (!imageBufferUpdated) {
			fxAwaitingNewImage = true;
			return;
		}
		imageBufferUpdated = false;

		// Draw the GL frame buffer snapshot directly through the pixel painter.
		canvas.drawFrame(buffer, bufferImageWidth, bufferImageHeight, ++imageFrameId);
		canvas.commit();
		fxAwaitingNewImage = true;
	}

	/**
	 * Extended pixel canvas to allow absolute sizing via {@link AbsoluteSizedCanvas#size(double, double)}.
	 */
	private static class AbsoluteSizedCanvas extends PixelCanvas {
		private final GLPixelPainter pixelPainter;

		public AbsoluteSizedCanvas(@Nonnull GLPixelPainter pixelPainter) {
			super(pixelPainter);
			this.pixelPainter = pixelPainter;
		}

		protected void size(double w, double h) {
			setWidth(w);
			setHeight(h);
		}

		protected void drawFrame(@Nonnull ByteBuffer frameBuffer, int frameWidth, int frameHeight, long frameId) {
			pixelPainter.setFrame(frameBuffer, frameWidth, frameHeight);
			updateDrawHash(Long.hashCode(frameId));
		}
	}

	/**
	 * Pixel painter that commits the GL read buffer directly to {@link PixelWriter}, flipping the Y-axis during commit.
	 */
	private static class GLPixelPainter implements PixelPainter<ByteBuffer> {
		private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.wrap(new byte[0]);
		private ByteBuffer frameBuffer = EMPTY_BUFFER;
		private int frameWidth;
		private int frameHeight;
		private int imageWidth;
		private int imageHeight;

		@Override
		public boolean initialize(int width, int height) {
			boolean changed = imageWidth != width || imageHeight != height;
			imageWidth = width;
			imageHeight = height;
			return changed;
		}

		@Override
		public void release() {
			frameBuffer = EMPTY_BUFFER;
			frameWidth = 0;
			frameHeight = 0;
			imageWidth = 0;
			imageHeight = 0;
		}

		@Override
		public void commit(@Nonnull PixelWriter pixelWriter) {
			int width = Math.min(imageWidth, frameWidth);
			int height = Math.min(imageHeight, frameHeight);
			if (width <= 0 || height <= 0)
				return;

			for (int y = 0; y < height; y++) {
				int bufferRowOffset = (frameHeight - 1 - y) * frameWidth * COLOR_BYTES;
				ByteBuffer rowBuffer = frameBuffer.duplicate();
				rowBuffer.position(bufferRowOffset);
				pixelWriter.setPixels(0, y, width, 1, getPixelFormat(), rowBuffer, frameWidth * COLOR_BYTES);
			}
		}

		@Override
		public void fillRect(int x, int y, int width, int height, int color) {
			// no-op
		}

		@Override
		public void drawImage(int x, int y, @Nonnull ArgbSource image) {
			// no-op
		}

		@Override
		public void drawImage(int x, int y, int sx, int sy, int sw, int sh, @Nonnull ArgbSource image) {
			// no-op
		}

		@Override
		public void setColor(int x, int y, int color) {
			// no-op
		}

		@Override
		public void clear() {
			// no-op
		}

		@Nonnull
		@Override
		public ByteBuffer getBuffer() {
			return frameBuffer;
		}

		@Nonnull
		@Override
		public PixelFormat<ByteBuffer> getPixelFormat() {
			return PixelFormat.getByteBgraInstance();
		}

		private void setFrame(@Nonnull ByteBuffer frameBuffer, int frameWidth, int frameHeight) {
			this.frameBuffer = frameBuffer;
			this.frameWidth = Math.max(1, frameWidth);
			this.frameHeight = Math.max(1, frameHeight);
		}
	}
}
