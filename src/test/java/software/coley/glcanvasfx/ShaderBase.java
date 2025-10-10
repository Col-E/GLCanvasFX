package software.coley.glcanvasfx;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.glu.GLU;
import jakarta.annotation.Nonnull;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Common base for shader use.
 *
 * @author Matt Coley
 */
public abstract class ShaderBase implements GLEventListener {
	protected static final GLU glu = new GLU();
	protected float rx, ry;
	protected float mx, my;

	protected abstract void updateUniformVars(@Nonnull GL2 gl);

	protected int compile(@Nonnull GLAutoDrawable drawable, @Nonnull String vertexShader, @Nonnull String fragmentShader) {
		GL2 gl = drawable.getGL().getGL2();

		int vertexShaderProgram = gl.glCreateShader(GL2.GL_VERTEX_SHADER);
		int fragmentShaderProgram = gl.glCreateShader(GL2.GL_FRAGMENT_SHADER);

		String[] vsrc = new String[]{vertexShader};
		gl.glShaderSource(vertexShaderProgram, 1, vsrc, null, 0);
		gl.glCompileShader(vertexShaderProgram);

		String[] fsrc = new String[]{fragmentShader};
		gl.glShaderSource(fragmentShaderProgram, 1, fsrc, null, 0);
		gl.glCompileShader(fragmentShaderProgram);

		int shaderProgram = gl.glCreateProgram();
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
		return shaderProgram;
	}

	public void setMouseXY(double x, double y) {
		mx = (float) x;
		my = ry - (float) y; // Convert FX coordinate to shader coordinate
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
		rx = width;
		ry = height;
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
}
