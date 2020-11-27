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
package com.abdulmateen.arcoredemo.common.samplerender

import android.opengl.GLES30
import android.util.Log
import com.abdulmateen.arcoredemo.common.samplerender.GLError.maybeLogGLError
import com.abdulmateen.arcoredemo.common.samplerender.GLError.maybeThrowGLException
import de.javagl.obj.Obj
import de.javagl.obj.ObjData
import de.javagl.obj.ObjReader
import de.javagl.obj.ObjUtils
import java.io.Closeable
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * A collection of vertices, faces, and other attributes that define how to render a 3D object.
 *
 *
 * To render the mesh, use [SampleRender.draw].
 */
class Mesh(
    render: SampleRender?,
    primitiveMode: PrimitiveMode,
    indexBuffer: IndexBuffer?,
    vertexBuffers: Array<VertexBuffer>?
) : Closeable {
    /**
     * The kind of primitive to render.
     *
     *
     * This determines how the data in [VertexBuffer]s are interpreted. See [here](https://www.khronos.org/opengl/wiki/Primitive) for more on how primitives
     * behave.
     */
    enum class PrimitiveMode(/* package-private */
        val glesEnum: Int
    ) {
        POINTS(GLES30.GL_POINTS), LINE_STRIP(GLES30.GL_LINE_STRIP), LINE_LOOP(GLES30.GL_LINE_LOOP), LINES(
            GLES30.GL_LINES
        ),
        TRIANGLE_STRIP(GLES30.GL_TRIANGLE_STRIP), TRIANGLE_FAN(GLES30.GL_TRIANGLE_FAN), TRIANGLES(
            GLES30.GL_TRIANGLES
        );

    }

    private val vertexArrayId = intArrayOf(0)
    private val primitiveMode: PrimitiveMode
    private val indexBuffer: IndexBuffer?
    private val vertexBuffers: Array<VertexBuffer>
    override fun close() {
        if (vertexArrayId[0] != 0) {
            GLES30.glDeleteVertexArrays(1, vertexArrayId, 0)
            maybeLogGLError(
                Log.WARN,
                TAG,
                "Failed to free vertex array object",
                "glDeleteVertexArrays"
            )
        }
    }

    /* package-private */
    fun draw() {
        check(vertexArrayId[0] != 0) { "Tried to draw a freed Mesh" }
        GLES30.glBindVertexArray(vertexArrayId[0])
        maybeThrowGLException("Failed to bind vertex array object", "glBindVertexArray")
        if (indexBuffer == null) {
            // Sanity check for debugging
            val numberOfVertices = vertexBuffers[0].numberOfVertices
            for (i in 1 until vertexBuffers.size) {
                check(vertexBuffers[i].numberOfVertices == numberOfVertices) { "Vertex buffers have mismatching numbers of vertices" }
            }
            GLES30.glDrawArrays(primitiveMode.glesEnum, 0, numberOfVertices)
            maybeThrowGLException("Failed to draw vertex array object", "glDrawArrays")
        } else {
            GLES30.glDrawElements(
                primitiveMode.glesEnum, indexBuffer.size, GLES30.GL_UNSIGNED_INT, 0
            )
            maybeThrowGLException(
                "Failed to draw vertex array object with indices", "glDrawElements"
            )
        }
    }

    companion object {
        private val TAG = Mesh::class.java.simpleName

        /**
         * Constructs a [Mesh] from the given Wavefront OBJ file.
         *
         *
         * The [Mesh] will be constructed with three attributes, indexed in the order of local
         * coordinates (location 0, vec3), texture coordinates (location 1, vec2), and vertex normals
         * (location 2, vec3).
         */
        @Throws(IOException::class)
        fun createFromAsset(render: SampleRender, assetFileName: String?): Mesh {
            render.assets.open(assetFileName!!).use { inputStream ->
                val obj: Obj = ObjUtils.convertToRenderable(ObjReader.read(inputStream))

                // Obtain the data from the OBJ, as direct buffers:
                val vertexIndices: IntBuffer =
                    ObjData.getFaceVertexIndices(obj,  /*numVerticesPerFace=*/3)
                val localCoordinates: FloatBuffer = ObjData.getVertices(obj)
                val textureCoordinates: FloatBuffer =
                    ObjData.getTexCoords(obj,  /*dimensions=*/2)
                val normals: FloatBuffer = ObjData.getNormals(obj)
                val vertexBuffers = arrayOf(
                    VertexBuffer(render, 3, localCoordinates),
                    VertexBuffer(render, 2, textureCoordinates),
                    VertexBuffer(render, 3, normals)
                )
                val indexBuffer = IndexBuffer(render, vertexIndices)
                return Mesh(render, PrimitiveMode.TRIANGLES, indexBuffer, vertexBuffers)
            }
        }
    }

    /**
     * Construct a [Mesh].
     *
     *
     * The data in the given [IndexBuffer] and [VertexBuffer]s does not need to be
     * finalized; they may be freely changed throughout the lifetime of a [Mesh] using their
     * respective `set()` methods.
     *
     *
     * The ordering of the `vertexBuffers` is significant. Their array indices will
     * correspond to their attribute locations, which must be taken into account in shader code. The
     * [layout qualifier](https://www.khronos.org/opengl/wiki/Layout_Qualifier_(GLSL)) must
     * be used in the vertex shader code to explicitly associate attributes with these indices.
     */
    init {
        require(!(vertexBuffers == null || vertexBuffers.size == 0)) { "Must pass at least one vertex buffer" }
        this.primitiveMode = primitiveMode
        this.indexBuffer = indexBuffer
        this.vertexBuffers = vertexBuffers
        try {
            // Create vertex array
            GLES30.glGenVertexArrays(1, vertexArrayId, 0)
            maybeThrowGLException("Failed to generate a vertex array", "glGenVertexArrays")

            // Bind vertex array
            GLES30.glBindVertexArray(vertexArrayId[0])
            maybeThrowGLException("Failed to bind vertex array object", "glBindVertexArray")
            if (indexBuffer != null) {
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.bufferId)
            }
            for (i in vertexBuffers.indices) {
                // Bind each vertex buffer to vertex array
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffers[i].bufferId)
                maybeThrowGLException("Failed to bind vertex buffer", "glBindBuffer")
                GLES30.glVertexAttribPointer(
                    i,
                    vertexBuffers[i].numberOfEntriesPerVertex,
                    GLES30.GL_FLOAT,
                    false,
                    0,
                    0
                )
                maybeThrowGLException(
                    "Failed to associate vertex buffer with vertex array", "glVertexAttribPointer"
                )
                GLES30.glEnableVertexAttribArray(i)
                maybeThrowGLException(
                    "Failed to enable vertex buffer", "glEnableVertexAttribArray"
                )
            }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }
}