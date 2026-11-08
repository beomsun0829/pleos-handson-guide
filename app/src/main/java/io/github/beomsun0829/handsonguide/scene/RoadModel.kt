package io.github.beomsun0829.handsonguide.scene

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RoadPrimitive(val vertices: FloatArray, val color: List<Float>, val tintable: Boolean, val role: String)
data class RoadMesh(val name: String, val primitives: List<RoadPrimitive>)

/** Strict reader for our texture-free GLB profile: expanded TRIANGLES, FLOAT POSITION/NORMAL. */
object RoadModel {
    fun parse(bytes: ByteArray): Map<String, RoadMesh> {
        require(bytes.size in 28..16_777_216)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(data.int==0x46546c67 && data.int==2 && data.int==bytes.size)
        val jsonLength = data.int
        require(jsonLength>0 && jsonLength%4==0 && jsonLength<=data.remaining()-4)
        require(data.int==0x4e4f534a)
        val text = ByteArray(jsonLength); data.get(text)
        val json = JSONObject(text.toString(Charsets.UTF_8).trimEnd(' ','\u0000'))
        require(json.getJSONObject("asset").getString("version")=="2.0")
        require((json.optJSONArray("extensionsRequired")?.length() ?: 0) == 0)
        require(data.remaining()>=8)
        val binLength = data.int
        require(data.int==0x004e4942 && binLength==data.remaining())
        val binStart = data.position()
        val buffers = json.getJSONArray("buffers")
        require(buffers.length()==1 && !buffers.getJSONObject(0).has("uri"))
        val declaredLength = buffers.getJSONObject(0).getInt("byteLength")
        require(declaredLength in (binLength-3)..binLength)
        val views = json.getJSONArray("bufferViews")
        val accessors = json.getJSONArray("accessors")
        val materials = json.getJSONArray("materials")
        fun vectors(index: Int): List<FloatArray> {
            val a = accessors.getJSONObject(index)
            require(a.getInt("componentType")==5126 && a.getString("type")=="VEC3" && !a.has("sparse"))
            val view = views.getJSONObject(a.getInt("bufferView"))
            require(view.getInt("buffer")==0)
            val count = a.getInt("count")
            val stride = view.optInt("byteStride",12)
            val offset = view.optInt("byteOffset",0).toLong()
            val local = a.optInt("byteOffset",0).toLong()
            val length = view.getInt("byteLength").toLong()
            require(count in 1..120000 && stride>=12 && stride%4==0 && offset>=0 && local>=0)
            require(offset+length<=declaredLength && local+(count-1L)*stride+12<=length)
            return (0 until count).map { i ->
                val at = (binStart+offset+local+i.toLong()*stride).toInt()
                FloatArray(3) { j -> data.getFloat(at+j*4).also { require(it.isFinite()) } }
            }
        }
        val meshes = json.getJSONArray("meshes")
        return (0 until meshes.length()).map { i ->
            val mesh = meshes.getJSONObject(i)
            val array = mesh.getJSONArray("primitives")
            val primitives = (0 until array.length()).map { j ->
                val p = array.getJSONObject(j)
                require(p.optInt("mode",4)==4 && !p.has("indices") && !p.has("targets"))
                val attributes = p.getJSONObject("attributes")
                val positions = vectors(attributes.getInt("POSITION"))
                val normals = vectors(attributes.getInt("NORMAL"))
                require(positions.size==normals.size && positions.size%3==0)
                val packed = FloatArray(positions.size*6)
                positions.indices.forEach { n ->
                    positions[n].copyInto(packed,n*6)
                    normals[n].copyInto(packed,n*6+3)
                }
                val material = materials.getJSONObject(p.getInt("material"))
                val pbr = material.getJSONObject("pbrMetallicRoughness")
                require(!pbr.has("baseColorTexture") && !material.has("normalTexture"))
                val color = pbr.getJSONArray("baseColorFactor")
                require(color.length()==4)
                val rgba = (0..3).map { color.getDouble(it).toFloat().also { v -> require(v.isFinite() && v in 0f..1f) } }
                val extras = material.optJSONObject("extras")
                RoadPrimitive(packed,rgba,extras?.optBoolean("tintable")==true,extras?.optString("role","surface") ?: "surface")
            }
            RoadMesh(mesh.getString("name"),primitives)
        }.also { list -> require(list.map { it.name }.distinct().size==list.size) }.associateBy { it.name }
    }
}
