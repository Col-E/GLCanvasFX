package software.coley.glcanvasfx;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import jakarta.annotation.Nonnull;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;

import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.nio.ByteBuffer;

/**
 * A {@link Canvas} wrapper that draws {@link GLAutoDrawable} contents.
 *
 * @author Matt Coley
 */
public class GLCanvas extends BorderPane implements GLEventListener {
	/** Max width of any monitor in the current {@link GraphicsEnvironment} */
	private static final int MAX_WIDTH;
	/** Max height of any monitor in the current {@link GraphicsEnvironment} */
	private static final int MAX_HEIGHT;

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

	/** We use {@link javafx.scene.image.PixelFormat#getByteBgraInstance()} for compatibility with {@link com.jogamp.opengl.GL#GL_BGRA}. */
	private static final int COLOR_BYTES = 4;
	/** Canvas to draw to. */
	private final Canvas canvas = new Canvas();
	/** Canvas drawing context. */
	private final GraphicsContext gc = canvas.getGraphicsContext2D();
	/** Intermediate buffer to communicate between our {@link #image WritableImage} and the {@link GLAutoDrawable#getGL() GL} frame buffer. */
	private final ByteBuffer buffer;
	/** The image to draw to the {@link #canvas} which holds a snapshot of the {@link GLAutoDrawable#getGL() GL} frame buffer. */
	private WritableImage image;
	/** Current width of {@link #image}. */
	private int imageWidth;
	/** Current height of {@link #image}. */
	private int imageHeight;
	/** Last millis timestamp of a resize event observed. Used to prevent visual tearing when updating the {@link #canvas} display. */
	private long lastResizeTimeMs;
	/** Flag indicating if {@link #display(GLAutoDrawable)} needs to update {@link #image}. */
	boolean fxAwaitingNewImage = true;
	/** Flag indicating if {@link #displayCanvas()} has new contents in {@link #image} to draw to the {@link #canvas}. */
	boolean imageBufferUpdated;

	/**
	 * Constructs a canvas that will draw the contents of the given drawable.
	 *
	 * @param drawable
	 * 		Drawable to pull graphics from.
	 */
	public GLCanvas(@Nonnull GLAutoDrawable drawable) {
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
		// into a JavaFX WritableImage to be rendered later.
		drawable.addGLEventListener(this);

		// Register an FX animation timer that will draw the current WritableImage contents
		// to the canvas every FX render pulse. This will only refresh the canvas when:
		//  - The canvas is present in some scene graph
		//  - The canvas is visible
		//  - The canvas is enabled
		AnimationTimer animation = new AnimationTimer() {
			@Override
			public void handle(long now) {
				displayCanvas();
			}
		};
		sceneProperty().isNotNull()
				.and(visibleProperty())
				.and(disabledProperty().not())
				.addListener((_, _, doRender) -> {
					if (doRender)
						animation.start();
					else
						animation.stop();
				});
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
		canvas.setWidth(w);
		canvas.setHeight(h);
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
		// Don't update the image buffer if our image isn't initialized yet.
		if (image == null)
			return;

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

		// Update our image.
		buffer.position(0);
		PixelWriter writer = image.getPixelWriter();
		writer.setPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), buffer, w * COLOR_BYTES);
		imageBufferUpdated = true;
	}

	@Override
	public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
		lastResizeTimeMs = System.currentTimeMillis();
		refitBuffers(width, height);
	}

	/**
	 * Updates our {@link #image} and {@link #buffer} to fit the new GL viewport dimensions.
	 *
	 * @param width
	 * 		New viewport width.
	 * @param height
	 * 		New viewport height.
	 */
	private void refitBuffers(int width, int height) {
		imageWidth = Math.max(1, width);
		imageHeight = Math.max(1, height);
		buffer.limit((imageWidth * imageHeight * COLOR_BYTES) + COLOR_BYTES);
		image = new WritableImage(imageWidth, imageHeight);
	}

	/**
	 * Called every FX animation pulse via the {@link AnimationTimer} managed in the constructor.
	 * <p/>
	 * Every frame we check if we have new contents in our {@link #image} assigned in {@link #display(GLAutoDrawable)}.
	 * If we do, we'll draw the contents on the canvas.
	 */
	private void displayCanvas() {
		fxAwaitingNewImage = true;

		// Don't update the canvas if our image hasn't been updated since our last draw.
		if (!imageBufferUpdated)
			return;
		imageBufferUpdated = false;

		// Transform the coordinate space of the image buffer so that the y-axis is inverted.
		//  - Effectively: scale(1, -1).translate(0, -h)
		gc.setTransform(1, 0, 0, -1, 0, Math.max(1, imageHeight));

		// Draw the image.
		gc.drawImage(image, 0, 0);
	}
}
