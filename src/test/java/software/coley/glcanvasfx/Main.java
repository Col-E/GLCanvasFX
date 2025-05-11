package software.coley.glcanvasfx;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.AnimatorBase;
import com.jogamp.opengl.util.FPSAnimator;
import jakarta.annotation.Nonnull;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.DisplacementMap;
import javafx.scene.effect.FloatMap;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.effect.Glow;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Basic OpenGL rotating triangle application in a JavaFX context via {@link GLCanvas}.
 *
 * @author Matt Coley
 */
public class Main {
	private static final int FPS = 144;
	private static final Showcase showcase = Showcase.SHADER;
	private static final GLCapabilities capabilities = createDefaultCapabilities();
	private static final GLDrawableFactory factory = GLDrawableFactory.getFactory(capabilities.getGLProfile());

	/**
	 * @param args
	 * 		Launch args to pass to {@link Application}.
	 */
	public static void main(String[] args) {
		App.launch(App.class, args);
	}

	/**
	 * @return GL capabilities of the current system.
	 */
	@Nonnull
	private static GLCapabilities createDefaultCapabilities() {
		GLProfile profile = GLProfile.getMaxProgrammable(true);
		GLCapabilities capabilities = new GLCapabilities(profile);
		capabilities.setDepthBits(24);
		return capabilities;
	}

	/**
	 * FX application to display a {@link GLCanvas}.
	 */
	public static class App extends Application {
		@Override
		public void start(Stage stage) {
			GLCanvas canvas = newCanvas();

			// Create some buttons to apply effects to the canvas
			Button none = new Button("None");
			Button glow = new Button("Glow");
			Button blur = new Button("Blur");
			Button wiggle = new Button("Wiggle");
			Button hue = new Button("Hue");
			none.setOnMousePressed(_ -> canvas.setEffect(null));
			glow.setOnMousePressed(_ -> canvas.setEffect(new Glow(1.0)));
			blur.setOnMousePressed(_ -> canvas.setEffect(new GaussianBlur(10)));
			wiggle.setOnMousePressed(_ -> {
				FloatMap map = new FloatMap();
				map.widthProperty().bind(canvas.widthProperty());
				map.heightProperty().bind(canvas.heightProperty());

				for (int x = 0; x < map.getWidth(); x++) {
					double v = (Math.sin(x / 20.0 * Math.PI) - 0.5) / 40.0;
					for (int y = 0; y < map.getHeight(); y++) {
						map.setSamples(x, y, 0.0f, (float) v);
					}
				}

				canvas.setEffect(new DisplacementMap(map));
			});
			hue.setOnMousePressed(_ -> {
				DoubleProperty hueShift = new SimpleDoubleProperty();
				AnimationTimer timer = new AnimationTimer() {
					double shift = 0;

					@Override
					public void handle(long now) {
						shift += 0.01;
						hueShift.set(Math.cos(shift));
					}
				};
				timer.start();

				ColorAdjust adjust = new ColorAdjust();
				adjust.hueProperty().bind(hueShift);
				canvas.setEffect(adjust);
			});

			// Button layout
			HBox effects = new HBox(none, glow, blur, wiggle, hue);
			effects.setSpacing(10);
			Group effectsWrapper = new Group(effects);
			StackPane stack = new StackPane(canvas, effectsWrapper);
			StackPane.setAlignment(effectsWrapper, Pos.BOTTOM_RIGHT);
			StackPane.setMargin(effectsWrapper, new Insets(10));

			// App layout
			BorderPane root = new BorderPane(stack);
			root.setBackground(new Background(new BackgroundFill(Color.BLACK, null, null)));

			// Show the scene
			Scene scene = new Scene(root, 800, 600);
			stage.getIcons().add(new Image("/the_hello_world_triangle.png"));
			stage.setTitle("JOGL + JavaFX");
			stage.setOnHiding(_ -> System.exit(0));
			stage.setScene(scene);
			stage.show();
		}

		/**
		 * @return New canvas instance with its own rotating triangle.
		 */
		@Nonnull
		public static GLCanvas newCanvas() {
			GLOffscreenAutoDrawable drawable = factory.createOffscreenAutoDrawable(factory.getDefaultDevice(), capabilities, null, 800, 600);

			switch (showcase) {
				case SPINNING_TRIANGLE -> drawable.addGLEventListener(new SpinningTriangleDemo());
				case SHADER -> drawable.addGLEventListener(new ShaderDemo());
			}


			// Begin JOGL animation loop
			AnimatorBase animator = new FPSAnimator(FPS);
			animator.add(drawable);
			animator.setUpdateFPSFrames(FPS, System.out);
			animator.start();

			// Create the canvas and inform the wrapped drawable of any size changes
			GLCanvas canvas = new GLCanvas(drawable);
			canvas.widthProperty().addListener((_, _, _) -> drawable.setSurfaceSize(Math.max(1, (int) canvas.getWidth()), Math.max(1, (int) canvas.getHeight())));
			canvas.heightProperty().addListener((_, _, _) -> drawable.setSurfaceSize(Math.max(1, (int) canvas.getWidth()), Math.max(1, (int) canvas.getHeight())));

			return canvas;
		}
	}

	/**
	 * Example shader use from: <a href="https://jvm-gaming.org/t/glsl-example-s-in-jogl/34884/2">Morten Nobel @ jvm-gaming.org</a>
	 */
	private static class ShaderDemo implements GLEventListener {
		private static final GLU glu = new GLU();
		// config
		private static final float x = -2.5f;
		private static final float y = -2;
		private static final float height = 4;
		private static final float width = 4;
		private static final int iterations = 150;
		// state
		private int shaderProgram;

		@Override
		public void init(GLAutoDrawable drawable) {
			GL2 gl = drawable.getGL().getGL2();

			int vertexShaderProgram = gl.glCreateShader(GL2.GL_VERTEX_SHADER);
			int fragmentShaderProgram = gl.glCreateShader(GL2.GL_FRAGMENT_SHADER);

			String[] vsrc = new String[]{"""
				uniform float mandel_x;
				uniform float mandel_y;
				uniform float mandel_width;
				uniform float mandel_height;
				uniform float mandel_iterations;
				
				void main()
				{
					gl_TexCoord[0] = gl_MultiTexCoord0;
					gl_Position = ftransform();
				}"""
			};
			gl.glShaderSource(vertexShaderProgram, 1, vsrc, null, 0);
			gl.glCompileShader(vertexShaderProgram);

			String[] fsrc = new String[]{"""
				uniform float mandel_x;
				uniform float mandel_y;
				uniform float mandel_width;
				uniform float mandel_height;
				uniform float mandel_iterations;
				
				float calculateMandelbrotIterations(float x, float y) {
					float xx = 0.0;
				    float yy = 0.0;
				    float iter = 0.0;
				    while (xx * xx + yy * yy <= 4.0 && iter<mandel_iterations) {
				        float temp = xx*xx - yy*yy + x;
				        yy = 2.0*xx*yy + y;
				
				        xx = temp;
				
				        iter ++;
				    }
				    return iter;
				}
				
				vec4 getColor(float iterations) {
					float oneThirdMandelIterations = mandel_iterations/3.0;
					float green = iterations/oneThirdMandelIterations;
					float blue = (iterations-1.3*oneThirdMandelIterations)/oneThirdMandelIterations;
					float red = (iterations-2.2*oneThirdMandelIterations)/oneThirdMandelIterations;
					return vec4(red,green,blue,1.0);
				}
				
				void main()
				{
					float x = mandel_x+gl_TexCoord[0].x*mandel_width;
					float y = mandel_y+gl_TexCoord[0].y*mandel_height;
					float iterations = calculateMandelbrotIterations(x,y);
					gl_FragColor = getColor(iterations);
				}"""
			};
			gl.glShaderSource(fragmentShaderProgram, 1, fsrc, null, 0);
			gl.glCompileShader(fragmentShaderProgram);

			shaderProgram = gl.glCreateProgram();
			gl.glAttachShader(shaderProgram, vertexShaderProgram);
			gl.glAttachShader(shaderProgram, fragmentShaderProgram);
			gl.glLinkProgram(shaderProgram);
			gl.glValidateProgram(shaderProgram);
			IntBuffer intBuffer = IntBuffer.allocate(1);
			gl.glGetProgramiv(shaderProgram, GL2.GL_LINK_STATUS, intBuffer);
			if (intBuffer.get(0) != 1) {
				gl.glGetProgramiv(shaderProgram, GL2.GL_INFO_LOG_LENGTH, intBuffer);
				int size = intBuffer.get(0);
				System.err.println("Program link error: ");
				if (size > 0) {
					ByteBuffer byteBuffer = ByteBuffer.allocate(size);
					gl.glGetProgramInfoLog(shaderProgram, size, intBuffer, byteBuffer);
					for (byte b : byteBuffer.array()) {
						System.err.print((char) b);
					}
				} else {
					System.out.println("Unknown");
				}
				System.exit(1);
			}
			gl.glUseProgram(shaderProgram);
		}

		@Override
		public void dispose(GLAutoDrawable drawable) {
			// no-op
		}

		@Override
		public void display(GLAutoDrawable drawable) {
			GL2 gl = drawable.getGL().getGL2();
			updateUniformVars(gl);
			gl.glLoadIdentity();
			gl.glBegin(GL2.GL_QUADS);
			{
				gl.glTexCoord2f(0.0f, 1.0f);
				gl.glVertex3f(0.0f, 1.0f, 1.0f);
				gl.glTexCoord2f(1.0f, 1.0f);
				gl.glVertex3f(1.0f, 1.0f, 1.0f);
				gl.glTexCoord2f(1.0f, 0.0f);
				gl.glVertex3f(1.0f, 0.0f, 1.0f);
				gl.glTexCoord2f(0.0f, 0.0f);
				gl.glVertex3f(0.0f, 0.0f, 1.0f);
			}
			gl.glEnd();
			gl.glFlush();
		}

		@Override
		public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
			GL2 gl = drawable.getGL().getGL2();
			if (height <= 0)
				height = 1;
			gl.glViewport(0, 0, width, height);
			gl.glMatrixMode(GL2.GL_PROJECTION);
			gl.glLoadIdentity();
			glu.gluOrtho2D(0, 1, 0, 1);
			gl.glMatrixMode(GL2.GL_MODELVIEW);
			gl.glLoadIdentity();
		}

		private void updateUniformVars(GL2 gl) {
			int mandel_x = gl.glGetUniformLocation(shaderProgram, "mandel_x");
			int mandel_y = gl.glGetUniformLocation(shaderProgram, "mandel_y");
			int mandel_width = gl.glGetUniformLocation(shaderProgram, "mandel_width");
			int mandel_height = gl.glGetUniformLocation(shaderProgram, "mandel_height");
			int mandel_iterations = gl.glGetUniformLocation(shaderProgram, "mandel_iterations");
			assert (mandel_x != -1);
			assert (mandel_y != -1);
			assert (mandel_width != -1);
			assert (mandel_height != -1);
			assert (mandel_iterations != -1);

			gl.glUniform1f(mandel_x, x);
			gl.glUniform1f(mandel_y, y);
			gl.glUniform1f(mandel_width, width);
			gl.glUniform1f(mandel_height, height);
			gl.glUniform1f(mandel_iterations, iterations);
		}
	}

	private static class SpinningTriangleDemo implements GLEventListener {
		private double theta = Math.random();

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
			theta += 0.01;

			double s = Math.sin(theta);
			double c = Math.cos(theta);

			// Draw the rotating triangle
			GL2 gl = drawable.getGL().getGL2();
			gl.glClear(GL.GL_COLOR_BUFFER_BIT);
			gl.glBegin(GL.GL_TRIANGLES);
			gl.glColor3f(1, 0, 0);
			gl.glVertex2d(-c, -c);
			gl.glColor3f(0, 1, 0);
			gl.glVertex2d(0, c);
			gl.glColor3f(0, 0, 1);
			gl.glVertex2d(s, -s);
			gl.glEnd();
		}

		@Override
		public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
			// no-op
		}
	}

	private enum Showcase {
		SPINNING_TRIANGLE, SHADER
	}
}
