package software.coley.glcanvasfx;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.animation.AnimationTimer;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import software.coley.bentofx.control.canvas.ArgbSource;
import software.coley.bentofx.control.canvas.PixelCanvas;
import software.coley.bentofx.control.canvas.PixelPainterIntArgb;

import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.nio.Buffer;
import java.nio.IntBuffer;

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

	/** Canvas to draw to. */
	private final AbsoluteSizedCanvas canvas = new AbsoluteSizedCanvas();
	/** Timer to animate canvas draws on. */
	private final AnimationTimer canvasAnimation;
	/** Intermediate buffer to communicate between our {@link #argbSource ARGB lookup} and the {@link GLAutoDrawable#getGL() GL} frame buffer. */
	private final IntBuffer buffer;
	/** The wrapper around our {@link #buffer} for image-like ARGB lookups. Holds a snapshot of the {@link GLAutoDrawable#getGL() GL} frame buffer. */
	private ArgbSource argbSource;
	/** Current width of {@link #argbSource display}. */
	private int imageWidth;
	/** Current height of {@link #argbSource display}. */
	private int imageHeight;
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
		// Buffer to hold MAX_WIDTH*MAX_HEIGHT pixels of ARGB data.
		//
		// We allocate a single array to the maximum possible size of the screen so that when
		// we observe the canvas changing size, we only have to update the buffer limit rather
		// than allocate a whole new buffer of the appropriate size. This saves loads of
		// memory when the user is fiddling around resizing things a lot & rapidly.
		int[] bufferBacking = new int[MAX_WIDTH * MAX_HEIGHT + 1];
		buffer = IntBuffer.wrap(bufferBacking);

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
		// Don't update the image buffer if our image isn't initialized yet.
		if (argbSource == null)
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
		// - I know, it says BGRA and earlier we said ARGB.
		//   It works and the colors are still correct (See test class for examples/proof).
		buffer.position(0);
		int w = Math.max(1, Math.min(drawable.getSurfaceWidth(), imageWidth));
		int h = Math.max(1, Math.min(drawable.getSurfaceHeight(), imageHeight));
		drawable.getGL().glReadPixels(0, 0, w, h, GL.GL_BGRA, GL2.GL_UNSIGNED_BYTE, buffer);

		// Notify UI next frame our buffer is updated and ready to redraw new contents.
		imageBufferUpdated = true;
	}

	@Override
	public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
		lastResizeTimeMs = System.currentTimeMillis();
		refitBuffers(width, height);
	}

	/**
	 * Updates our {@link #argbSource} and {@link #buffer} to fit the new GL viewport dimensions.
	 *
	 * @param width
	 * 		New viewport width.
	 * @param height
	 * 		New viewport height.
	 */
	private void refitBuffers(int width, int height) {
		imageWidth = Math.max(1, width);
		imageHeight = Math.max(1, height);
		buffer.limit(Math.max(buffer.limit(), imageWidth * imageHeight + 1));
		argbSource = new GLArgbSource(buffer, imageWidth, imageHeight);
	}

	/**
	 * Called every FX animation pulse via the {@link AnimationTimer} managed in the constructor.
	 * <p/>
	 * Every frame we check if we have new contents in our {@link #argbSource} assigned in {@link #display(GLAutoDrawable)}.
	 * If we do, we'll draw the contents on the canvas.
	 */
	private void displayCanvas() {
		fxAwaitingNewImage = true;

		// Don't update the canvas if our image hasn't been updated since our last draw.
		if (!imageBufferUpdated)
			return;
		imageBufferUpdated = false;

		// Draw the image.
		//
		// We don't clear the canvas to mitigate the built-in de-duplication of draw dispatches.
		// This gives us a marginal performance gain.
		canvas.drawImage(0, 0, argbSource);
		canvas.commit();
	}

	/**
	 * Extended pixel canvas to allow absolute sizing via {@link AbsoluteSizedCanvas#size(double, double)}.
	 */
	private static class AbsoluteSizedCanvas extends PixelCanvas {
		public AbsoluteSizedCanvas() {
			super(new PixelPainterIntArgb());
		}

		protected void size(double w, double h) {
			setWidth(w);
			setHeight(h);
		}
	}

	/**
	 * Our implementation of an {@link ArgbSource} for reading content out of the canvas {@link #buffer}.
	 * <br>
	 * This accommodates for the {@link GL#glReadPixels(int, int, int, int, int, int, Buffer)} giving us an Y-axis flipped image.
	 * <br>
	 * There is some weirdness where we read {@link #buffer} content as {@code ARGB} but the {@code glReadPixels} call is
	 * passed {@code BGRA} as the buffer content {@code type} instead as a series of {@link GL#GL_UNSIGNED_BYTE}.
	 * The setup we have here shouldn't be mapping the color bytes properly when I try and wrap my head around it,
	 * but it renders correctly somehow.
	 */
	private static class GLArgbSource implements ArgbSource {
		private final IntBuffer buffer;
		private final int width;
		private final int height;
		private final int bufferCapacity;

		private GLArgbSource(@Nonnull IntBuffer buffer, int width, int height) {
			this.buffer = buffer;
			this.width = width;
			this.height = height;

			bufferCapacity = width * height;
		}

		@Override
		public int getWidth() {
			return width;
		}

		@Override
		public int getHeight() {
			return height;
		}

		@Override
		public int getArgb(int x, int y) {
			int i = ((height - 1 - y) * width + x);
			if (i >= 0 && i < bufferCapacity) {
				return buffer.get(i);
			}
			return 0;
		}

		@Nullable
		@Override
		public int[] getArgb(int x, int y, int width, int height) {
			int yStart = Math.max(0, y);
			int xStart = Math.max(0, x);
			int yBound = Math.min(y + height, getHeight());
			int xBound = Math.min(x + width, getWidth());
			IntBuffer buffer = this.buffer;
			int bufferCapacity = this.bufferCapacity;
			int outCapacity = width * height;
			int[] out = new int[outCapacity];
			for (int ly = yStart; ly < yBound; ++ly) {
				int yBufferOffset = (getHeight() - 1 - ly) * getWidth();
				int yOutOffset = (ly - y) * width;

				for (int lx = xStart; lx < xBound; ++lx) {
					int outIndex = (yOutOffset + (lx - x));
					int bufferIndex = (yBufferOffset + lx);
					if (bufferIndex < bufferCapacity && outIndex < outCapacity) {
						int rgb = buffer.get(bufferIndex);
						out[outIndex] = rgb;
					}
				}
			}

			return out;
		}

		@Override
		public int hashCode() {
			return 0;
		}
	}
}
