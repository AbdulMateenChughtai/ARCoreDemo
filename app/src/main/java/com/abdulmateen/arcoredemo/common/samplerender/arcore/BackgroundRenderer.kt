/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.abdulmateen.arcoredemo.common.samplerender.arcore

import android.media.Image
import android.opengl.GLES30
import com.abdulmateen.arcoredemo.common.samplerender.*
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * This class both renders the AR camera background and composes the a scene foreground. The camera
 * background can be rendered as either camera image data or camera depth data. The virtual scene
 * can be composited with or without depth occlusion.
 */
class BackgroundRenderer(render: SampleRender?) {
    companion object {
        private val TAG = BackgroundRenderer::class.java.simpleName

        // components_per_vertex * number_of_vertices * float_size
        private const val COORDS_BUFFER_SIZE = 2 * 4 * 4
        private val NDC_QUAD_COORDS_BUFFER =
            ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val VIRTUAL_SCENE_TEX_COORDS_BUFFER =
            ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()

        init {
            NDC_QUAD_COORDS_BUFFER.put(
                floatArrayOf( /*0:*/
                    -1f, -1f,  /*1:*/+1f, -1f,  /*2:*/-1f, +1f,  /*3:*/+1f, +1f
                )
            )
            VIRTUAL_SCENE_TEX_COORDS_BUFFER.put(
                floatArrayOf( /*0:*/
                    0f, 0f,  /*1:*/1f, 0f,  /*2:*/0f, 1f,  /*3:*/1f, 1f
                )
            )
        }
    }

    private val cameraTexCoords =
        ByteBuffer.allocateDirect(COORDS_BUFFER_SIZE)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val mesh: Mesh
    private val cameraTexCoordsVertexBuffer: VertexBuffer
    private var backgroundShader: Shader? = null
    private var occlusionShader: Shader? = null
    private val cameraDepthTexture: Texture
    private val cameraColorTexture: Texture
    private var useDepthVisualization = false
    private var useOcclusion = false
    private var aspectRatio = 0f

    /**
     * Sets whether the background camera image should be replaced with a depth visualization instead.
     * This reloads the corresponding shader code, and must be called on the GL thread.
     */
    @Throws(IOException::class)
    fun setUseDepthVisualization(
        render: SampleRender?,
        useDepthVisualization: Boolean
    ) {
        if (backgroundShader != null) {
            if (this.useDepthVisualization == useDepthVisualization) {
                return
            }
            backgroundShader?.close()
            backgroundShader = null
            this.useDepthVisualization = useDepthVisualization
        }


        render?.let {
            backgroundShader = if (useDepthVisualization) {
                Shader.createFromAssets(
                    render,
                    "shaders/background_show_depth_color_visualization.vert",
                    "shaders/background_show_depth_color_visualization.frag",  /*defines=*/
                    null
                )
                    .setTexture("u_CameraDepthTexture", cameraDepthTexture)
                    .setDepthTest(false)
                    .setDepthWrite(false)
            } else {
                Shader.createFromAssets(
                    render,
                    "shaders/background_show_camera.vert",
                    "shaders/background_show_camera.frag",  /*defines=*/
                    null
                )
                    .setTexture("u_CameraColorTexture", cameraColorTexture)
                    .setDepthTest(false)
                    .setDepthWrite(false)
            }
        }

    }

    /**
     * Sets whether to use depth for occlusion. This reloads the shader code with new `#define`s, and must be called on the GL thread.
     */
    @Throws(IOException::class)
    fun setUseOcclusion(render: SampleRender?, useOcclusion: Boolean) {
        render?.let {
            if (occlusionShader != null) {
                if (this.useOcclusion == useOcclusion) {
                    return
                }
                occlusionShader?.close()
                occlusionShader = null
                this.useOcclusion = useOcclusion
            }
            val defines =
                HashMap<String, String>()
            defines["USE_OCCLUSION"] = if (useOcclusion) "1" else "0"
            occlusionShader = Shader.createFromAssets(
                render,
                "shaders/occlusion.vert",
                "shaders/occlusion.frag",
                defines
            )
                .setDepthTest(false)
                .setDepthWrite(false)
                .setBlend(Shader.BlendFactor.SRC_ALPHA, Shader.BlendFactor.ONE_MINUS_SRC_ALPHA)
            if (useOcclusion) {
                occlusionShader
                    ?.setTexture("u_CameraDepthTexture", cameraDepthTexture)
                    ?.setFloat("u_DepthAspectRatio", aspectRatio)
            }
        }

    }

    /**
     * Updates the display geometry. This must be called every frame before calling either of
     * BackgroundRenderer's draw methods.
     *
     * @param frame The current `Frame` as returned by [Session.update].
     */
    fun updateDisplayGeometry(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            // If display rotation changed (also includes view size change), we need to re-query the UV
            // coordinates for the screen rect, as they may have changed as well.
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                NDC_QUAD_COORDS_BUFFER,
                Coordinates2d.TEXTURE_NORMALIZED,
                cameraTexCoords
            )
            cameraTexCoordsVertexBuffer.set(cameraTexCoords)
        }
    }

    /** Update depth texture with Image contents.  */
    fun updateCameraDepthTexture(image: Image) {
        // SampleRender abstraction leaks here
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraDepthTexture.getTextureId())
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RG8,
            image.width,
            image.height,
            0,
            GLES30.GL_RG,
            GLES30.GL_UNSIGNED_BYTE,
            image.planes[0].buffer
        )
        if (useOcclusion) {
            aspectRatio = image.width.toFloat() / image.height.toFloat()
            occlusionShader?.setFloat("u_DepthAspectRatio", aspectRatio)
        }
    }

    /**
     * Draws the AR background image. The image will be drawn such that virtual content rendered with
     * the matrices provided by [com.google.ar.core.Camera.getViewMatrix] and
     * [com.google.ar.core.Camera.getProjectionMatrix] will
     * accurately follow static physical objects.
     */
    fun drawBackground(render: SampleRender) {
        backgroundShader?.let { render.draw(mesh, it) }
    }

    /**
     * Draws the virtual scene. Any objects rendered in the given [Framebuffer] will be drawn
     * given the previously specified [OcclusionMode].
     *
     *
     * Virtual content should be rendered using the matrices provided by [ ][com.google.ar.core.Camera.getViewMatrix] and [ ][com.google.ar.core.Camera.getProjectionMatrix].
     */
    fun drawVirtualScene(
        render: SampleRender,
        virtualSceneFramebuffer: Framebuffer,
        zNear: Float,
        zFar: Float
    ) {
        virtualSceneFramebuffer.getColorTexture()?.let {
            occlusionShader?.setTexture(
                "u_VirtualSceneColorTexture", it
            )
        }
        if (useOcclusion) {
            virtualSceneFramebuffer.getDepthTexture()?.let {
                occlusionShader
                    ?.setTexture("u_VirtualSceneDepthTexture", it)
                    ?.setFloat("u_ZNear", zNear)
                    ?.setFloat("u_ZFar", zFar)
            }
        }
        occlusionShader?.let { render.draw(mesh, it) }
    }

    /** Return the camera color texture generated by this object.  */
    fun getCameraColorTexture(): Texture {
        return cameraColorTexture
    }

    /** Return the camera depth texture generated by this object.  */
    fun getCameraDepthTexture(): Texture {
        return cameraDepthTexture
    }

    /**
     * Allocates and initializes OpenGL resources needed by the background renderer. Must be called
     * during a [SampleRender.Renderer] callback, typically in [ ][SampleRender.Renderer.onSurfaceCreated].
     */
    init {
        cameraColorTexture =
            Texture(render, Texture.Target.TEXTURE_EXTERNAL_OES, Texture.WrapMode.CLAMP_TO_EDGE)
        cameraDepthTexture =
            Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE)

        // Create a Mesh with three vertex buffers: one for the screen coordinates (normalized device
        // coordinates), one for the camera texture coordinates (to be populated with proper data later
        // before drawing), and one for the virtual scene texture coordinates (unit texture quad)
        val screenCoordsVertexBuffer = VertexBuffer(
            render,  /* numberOfEntriesPerVertex=*/
            2,
            NDC_QUAD_COORDS_BUFFER
        )
        cameraTexCoordsVertexBuffer =
            VertexBuffer(render,  /*numberOfEntriesPerVertex=*/2,  /*entries=*/null)
        val virtualSceneTexCoordsVertexBuffer = VertexBuffer(
            render,  /* numberOfEntriesPerVertex=*/
            2,
            VIRTUAL_SCENE_TEX_COORDS_BUFFER
        )
        val vertexBuffers: Array<VertexBuffer> = arrayOf<VertexBuffer>(
            screenCoordsVertexBuffer, cameraTexCoordsVertexBuffer, virtualSceneTexCoordsVertexBuffer
        )
        mesh = Mesh(render, Mesh.PrimitiveMode.TRIANGLE_STRIP,  /*indexBuffer=*/null, vertexBuffers)
    }
}