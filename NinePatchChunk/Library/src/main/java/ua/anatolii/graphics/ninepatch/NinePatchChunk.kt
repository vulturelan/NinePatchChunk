package ua.anatolii.graphics.ninepatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.NinePatchDrawable
import androidx.core.graphics.get
import ua.anatolii.graphics.ninepatch.BitmapType.Companion.determineBitmapType
import ua.anatolii.graphics.ninepatch.BitmapType.Companion.getNinePatchDrawable
import java.io.Externalizable
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInput
import java.io.ObjectOutput
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * Created by Anatolii on 8/27/13.
 */
class NinePatchChunk : Externalizable {
    /**
     * By default it's true
     */
    var wasSerialized: Boolean = true

    /**
     * Horizontal stretchable areas list.
     */
    var xDivs: ArrayList<Div> = arrayListOf()

    /**
     * Vertical stretchable areas list.
     */
    var yDivs: ArrayList<Div> = arrayListOf()

    /**
     * Content padding
     */
    @JvmField
    var padding: Rect = Rect()

    /**
     * Colors array for chunks. If not sure what it is - fill it with NO_COLOR value. Or just use createColorsArray() method with current chunk instance.
     */
    var colors: IntArray = intArrayOf()

    /**
     * Serializes current chunk instance to byte array. This array will pass thia check: NinePatch.isNinePatchChunk(byte[] chunk)
     *
     * @return The 9-patch data chunk describing how the underlying bitmap is split apart and drawn.
     */
    fun toBytes(): ByteArray {
        val capacity = 4 + (7 * 4) + xDivs.size * 2 * 4 + yDivs.size * 2 * 4 + colors.size * 4
        val byteBuffer = ByteBuffer.allocate(capacity).order(ByteOrder.nativeOrder())
        byteBuffer.put(1.toByte())
        byteBuffer.put((xDivs.size * 2).toByte())
        byteBuffer.put((yDivs.size * 2).toByte())
        byteBuffer.put(colors.size.toByte())
        //Skip
        byteBuffer.putInt(0)
        byteBuffer.putInt(0)

        byteBuffer.putInt(padding.left)
        byteBuffer.putInt(padding.right)
        byteBuffer.putInt(padding.top)
        byteBuffer.putInt(padding.bottom)

        //Skip
        byteBuffer.putInt(0)

        for (div in xDivs) {
            byteBuffer.putInt(div.start)
            byteBuffer.putInt(div.stop)
        }
        for (div in yDivs) {
            byteBuffer.putInt(div.start)
            byteBuffer.putInt(div.stop)
        }
        for (color in colors) byteBuffer.putInt(color)


        return byteBuffer.array()
    }

    @Throws(IOException::class)
    override fun readExternal(input: ObjectInput) {
        val length = input.readInt()
        val bytes = ByteArray(length)
        input.read(bytes)
        try {
            val patch = parse(bytes)
            this.wasSerialized = patch.wasSerialized
            this.xDivs = patch.xDivs
            this.yDivs = patch.yDivs
            this.padding = patch.padding
            this.colors = patch.colors
        } catch (e: DivLengthException) {
            //ignore
        } catch (e: ChunkNotSerializedException) {
            //ignore
        }
    }

    @Throws(IOException::class)
    override fun writeExternal(output: ObjectOutput) {
        val bytes = toBytes()
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    companion object {
        /**
         * The 9 patch segment is not a solid color.
         */
        const val NO_COLOR: Int = 0x00000001

        /**
         * The 9 patch segment is completely transparent.
         */
        const val TRANSPARENT_COLOR: Int = 0x00000000

        /**
         * Default density for image loading from some InputStream
         */
        const val DEFAULT_DENSITY: Int = 160

        /**
         * Creates new NinePatchChunk from byte array.
         * Note! In order to avoid some Runtime issues, please, do this check before using this method: NinePatch.isNinePatchChunk(byte[] chunk).
         *
         * @param data array of chunk data
         * @return parsed NinePatch chunk.
         * @throws DivLengthException          if there's no horizontal or vertical stretchable area at all.
         * @throws ChunkNotSerializedException if first bit is 0. I simply didn't face this case. If you will - feel free to contact me.
         * @throws BufferUnderflowException    if the position of reading buffer is equal or greater than limit (data array length).
         */
        @Throws(
            DivLengthException::class,
            ChunkNotSerializedException::class,
            BufferUnderflowException::class
        )
        fun parse(data: ByteArray): NinePatchChunk {
            val byteBuffer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())

            val chunk = NinePatchChunk()
            chunk.wasSerialized = byteBuffer.get().toInt() != 0
            if (!chunk.wasSerialized) throw ChunkNotSerializedException() //don't know how to handle


            val divXCount = byteBuffer.get()
            checkDivCount(divXCount)
            val divYCount = byteBuffer.get()
            checkDivCount(divYCount)

            chunk.colors = IntArray(byteBuffer.get().toInt())

            // skip 8 bytes
            byteBuffer.getInt() //position = 4
            byteBuffer.getInt() //position = 8

            chunk.padding.left = byteBuffer.getInt()
            chunk.padding.right = byteBuffer.getInt()
            chunk.padding.top = byteBuffer.getInt()
            chunk.padding.bottom = byteBuffer.getInt()

            // skip 4 bytes
            byteBuffer.getInt() //position = 28

            val xDivs = divXCount.toInt() shr 1
            chunk.xDivs = ArrayList(xDivs)
            readDivs(xDivs, byteBuffer, chunk.xDivs)

            val yDivs = divYCount.toInt() shr 1
            chunk.yDivs = ArrayList(yDivs)
            readDivs(yDivs, byteBuffer, chunk.yDivs)

            for (i in chunk.colors.indices) chunk.colors[i] = byteBuffer.getInt()

            return chunk
        }

        /**
         * Creates NinePatchDrawable right from raw Bitmap object. So resulting drawable will have width and height 2 pixels less if it is raw, not compiled 9-patch resource.
         *
         * @param bitmap  The bitmap describing the patches. Can be loaded from application resources
         * @param srcName The name of the source for the bitmap. Might be null.
         * @return new NinePatchDrawable object or null if bitmap parameter is null.
         */
        fun create9PatchDrawable(
            context: Context,
            bitmap: Bitmap,
            srcName: String?,
            extraPadding: Int = 0,
        ): NinePatchDrawable? {
            return getNinePatchDrawable(context.resources, bitmap, srcName, extraPadding)
        }

        /**
         * Creates NinePatchDrawable from inputStream.
         *
         * @param inputStream The input stream that holds the raw data to be decoded into a bitmap. Uses `DEFAULT_DENSITY` value for image decoding.
         * @param srcName     The name of the source for the bitmap. Might be null.
         * @return NinePatchDrawable instance.
         */
        fun create9PatchDrawable(
            context: Context,
            inputStream: InputStream?,
            srcName: String?,
        ): NinePatchDrawable {
            return create9PatchDrawable(context, inputStream, DEFAULT_DENSITY, srcName)
        }

        /**
         * Creates NinePatchDrawable from inputStream.
         *
         * @param inputStream  The input stream that holds the raw data to be decoded into a bitmap.
         * @param imageDensity density of the image if known in advance.
         * @param srcName      new NinePatchDrawable object or null if bitmap parameter is null.
         */
        fun create9PatchDrawable(
            context: Context,
            inputStream: InputStream?,
            imageDensity: Int,
            srcName: String?,
        ): NinePatchDrawable {
            val loadingResult = createChunkFromRawBitmap(context, inputStream, imageDensity)
            return loadingResult.getNinePatchDrawable(context.resources, srcName)
        }


        /**
         * * Creates NinePatchChunk instance from raw bitmap image. Method calls `isRawNinePatchBitmap`
         * method to make sure the bitmap is valid.
         *
         * @param bitmap source image
         * @return new instance of chunk or empty chunk if bitmap is null or some Exceptions happen.
         */
        fun createChunkFromRawBitmap(bitmap: Bitmap): NinePatchChunk {
            return try {
                createChunkFromRawBitmap(bitmap, true)
            } catch (e: RuntimeException) {
                createEmptyChunk()
            }
        }

        /**
         * Creates chunk from bitmap loaded from input stream.
         *
         * [inputStream] The input stream that holds the raw data to be decoded into a bitmap.
         * [imageDensity] density of the image if known in advance.
         * @return loading result which contains chunk object and loaded bitmap. Note! Resulting bitmap can be not the same as the source bitmap.
         */
        /**
         * Creates chunk from bitmap loaded from input stream. Uses `DEFAULT_DENSITY` value for image decoding.
         *
         * [inputStream] The input stream that holds the raw data to be decoded into a bitmap.
         * @return loading result which contains chunk object and loaded bitmap. Note! Resulting bitmap can be not the same as the source bitmap.
         */
        @JvmOverloads
        fun createChunkFromRawBitmap(
            context: Context,
            inputStream: InputStream?,
            imageDensity: Int = DEFAULT_DENSITY,
        ): ImageLoadingResult {
            val opts = BitmapFactory.Options()
            opts.inDensity = imageDensity
            opts.inTargetDensity = imageDensity
            val bitmap = BitmapFactory.decodeStream(inputStream, Rect(), opts)
            return createChunkFromRawBitmap(context, bitmap!!)
        }

        /**
         * Creates chunk from raw bitmap.
         *
         * @param bitmap  source image.
         * @return loading result which contains chunk object and loaded bitmap. Note! Resulting bitmap can be not the same as the source bitmap.
         */
        fun createChunkFromRawBitmap(context: Context, bitmap: Bitmap): ImageLoadingResult {
            var b = bitmap
            val type = determineBitmapType(b)
            val chunk = type.createChunk(b)
            b = type.modifyBitmap(context.resources, b, chunk)
            return ImageLoadingResult(b, chunk)
        }

        /**
         * Simply creates new empty NinePatchChunk object. You can use it to modify data as you want to.
         *
         * @return new NinePatchChunk instance.
         */
        fun createEmptyChunk(): NinePatchChunk {
            val out = NinePatchChunk()
            out.colors = IntArray(0)
            out.padding = Rect()
            out.yDivs = ArrayList()
            out.xDivs = ArrayList()
            return out
        }

        /**
         * Util method. Creates new colors array filled with NO_COLOR value according to current divs state and sets it to the chunk.
         *
         * @param chunk        chunk instance which contains divs information.
         * @param bitmapWidth  width of bitmap. Note! This value must be width without 9-patch borders. (2 pixels less then original 9.png image width)
         * @param bitmapHeight height of bitmap. Note! This value must be height without 9-patch borders. (2 pixels less then original 9.png image height)
         */
        fun createColorsArrayAndSet(chunk: NinePatchChunk?, bitmapWidth: Int, bitmapHeight: Int) {
            val colorsArray = createColorsArray(chunk, bitmapWidth, bitmapHeight)
            if (chunk != null) chunk.colors = colorsArray
        }

        /**
         * Util method. Creates new colors array according to current divs state.
         *
         * @param chunk        chunk instance which contains divs information.
         * @param bitmapWidth  width of bitmap. Note! This value must be width without 9-patch borders. (2 pixels less then original 9.png image width)
         * @param bitmapHeight height of bitmap. Note! This value must be height without 9-patch borders. (2 pixels less then original 9.png image height)
         * @return new properly sized array filled with NO_COLOR value.
         */
        fun createColorsArray(
            chunk: NinePatchChunk?,
            bitmapWidth: Int,
            bitmapHeight: Int,
        ): IntArray {
            if (chunk == null) return IntArray(0)
            val xRegions = getRegions(chunk.xDivs, bitmapWidth)
            val yRegions = getRegions(chunk.yDivs, bitmapHeight)
            val out = IntArray(xRegions.size * yRegions.size)
            Arrays.fill(out, NO_COLOR)
            return out
        }

        /**
         * Checks if bitmap is raw, not compiled 9-patch resource.
         *
         * @param bitmap source image
         * @return true if so and false if not or bitmap is null.
         */
        fun isRawNinePatchBitmap(bitmap: Bitmap?): Boolean {
            if (bitmap == null) return false
            if (bitmap.width < 3 || bitmap.height < 3) return false
            if (!isCornerPixelsAreTrasperent(bitmap)) return false
            if (!hasNinePatchBorder(bitmap)) return false
            return true
        }

        private fun hasNinePatchBorder(bitmap: Bitmap): Boolean {
            val width = bitmap.width
            val height = bitmap.height
            val lastXPixel = width - 1
            val lastYPixel = height - 1
            for (i in 1..<lastXPixel) {
                if (!isBorderPixel(bitmap[i, 0]) || !isBorderPixel(bitmap[i, lastYPixel])) return false
            }
            for (i in 1..<lastYPixel) {
                if (!isBorderPixel(bitmap[0, i]) || !isBorderPixel(bitmap[lastXPixel, i])) return false
            }
            if (getXDivs(bitmap, 0).isEmpty()) return false
            if (getXDivs(bitmap, lastYPixel).size > 1) return false
            if (getYDivs(bitmap, 0).isEmpty()) return false
            if (getYDivs(bitmap, lastXPixel).size > 1) return false
            return true
        }

        private fun isBorderPixel(tmpPixel1: Int): Boolean {
            return isTransparent(tmpPixel1) || isBlack(tmpPixel1)
        }

        private fun isCornerPixelsAreTrasperent(bitmap: Bitmap): Boolean {
            val lastYPixel = bitmap.height - 1
            val lastXPixel = bitmap.width - 1
            return isTransparent(bitmap[0, 0])
                    && isTransparent(bitmap[0, lastYPixel])
                    && isTransparent(bitmap[lastXPixel, 0])
                    && isTransparent(bitmap[lastXPixel, lastYPixel])
        }

        private fun isTransparent(color: Int): Boolean {
            return Color.alpha(color) == Color.TRANSPARENT
        }

        private fun isBlack(pixel: Int): Boolean {
            return pixel == Color.BLACK
        }

        @Throws(WrongPaddingException::class, DivLengthException::class)
        fun createChunkFromRawBitmap(
            bitmap: Bitmap,
            checkBitmap: Boolean,
        ): NinePatchChunk {
            if (checkBitmap && !isRawNinePatchBitmap(bitmap)) {
                return createEmptyChunk()
            }
            val out = NinePatchChunk()
            setupStretchableRegions(bitmap, out)
            setupPadding(bitmap, out)

            setupColors(bitmap, out)
            return out
        }

        private fun readDivs(divs: Int, byteBuffer: ByteBuffer, divArrayList: ArrayList<Div>) {
            for (i in 0..<divs) {
                val div = Div()
                div.start = byteBuffer.getInt()
                div.stop = byteBuffer.getInt()
                divArrayList.add(div)
            }
        }

        @Throws(DivLengthException::class)
        private fun checkDivCount(divCount: Byte) {
            if (divCount.toInt() == 0 || ((divCount.toInt() and 1) != 0)) {
                throw DivLengthException("Div count should be aliquot 2 and more then 0, but was: $divCount")
            }
        }

        private fun setupColors(bitmap: Bitmap, out: NinePatchChunk) {
            val bitmapWidth = bitmap.width - 2
            val bitmapHeight = bitmap.height - 2
            val xRegions = getRegions(out.xDivs, bitmapWidth)
            val yRegions = getRegions(out.yDivs, bitmapHeight)
            out.colors = IntArray(xRegions.size * yRegions.size)

            var colorIndex = 0
            for (yDiv in yRegions) {
                for (xDiv in xRegions) {
                    val startX = xDiv.start + 1
                    val startY = yDiv.start + 1
                    if (hasSameColor(bitmap, startX, xDiv.stop + 1, startY, yDiv.stop + 1)) {
                        var pixel = bitmap[startX, startY]
                        if (isTransparent(pixel)) pixel = TRANSPARENT_COLOR
                        out.colors[colorIndex] = pixel
                    } else {
                        out.colors[colorIndex] = NO_COLOR
                    }
                    colorIndex++
                }
            }
        }

        private fun hasSameColor(
            bitmap: Bitmap,
            startX: Int,
            stopX: Int,
            startY: Int,
            stopY: Int,
        ): Boolean {
            val color = bitmap[startX, startY]
            for (x in startX..stopX) {
                for (y in startY..stopY) {
                    if (color != bitmap[x, y]) return false
                }
            }
            return true
        }

        @Throws(WrongPaddingException::class)
        private fun setupPadding(bitmap: Bitmap, out: NinePatchChunk) {
            val maxXPixels = bitmap.width - 2
            val maxYPixels = bitmap.height - 2
            val xPaddings = getXDivs(bitmap, bitmap.height - 1)
            if (xPaddings.size > 1) throw WrongPaddingException("Raw padding is wrong. Should be only one horizontal padding region")
            val yPaddings = getYDivs(bitmap, bitmap.width - 1)
            if (yPaddings.size > 1) throw WrongPaddingException("Column padding is wrong. Should be only one vertical padding region")
            if (xPaddings.isEmpty()) xPaddings.add(out.xDivs[0])
            if (yPaddings.isEmpty()) yPaddings.add(out.yDivs[0])
            out.padding = Rect()
            out.padding.left = xPaddings[0].start
            out.padding.right = maxXPixels - xPaddings[0].stop
            out.padding.top = yPaddings[0].start
            out.padding.bottom = maxYPixels - yPaddings[0].stop
        }

        @Throws(DivLengthException::class)
        private fun setupStretchableRegions(bitmap: Bitmap, out: NinePatchChunk) {
            out.xDivs = getXDivs(bitmap, 0)
            if (out.xDivs.isEmpty()) throw DivLengthException("must be at least one horizontal stretchable region")
            out.yDivs = getYDivs(bitmap, 0)
            if (out.yDivs.isEmpty()) throw DivLengthException("must be at least one vertical stretchable region")
        }

        private fun getRegions(divs: ArrayList<Div>?, max: Int): ArrayList<Div> {
            val out = ArrayList<Div>()
            if (divs.isNullOrEmpty()) return out
            for (i in divs.indices) {
                val div = divs[i]
                if (i == 0 && div.start != 0) {
                    out.add(Div(0, div.start - 1))
                }
                if (i > 0) {
                    out.add(Div(divs[i - 1].stop, div.start - 1))
                }
                out.add(Div(div.start, div.stop - 1))
                if (i == divs.size - 1 && div.stop < max) {
                    out.add(Div(div.stop, max - 1))
                }
            }
            return out
        }

        private fun getYDivs(bitmap: Bitmap, column: Int): ArrayList<Div> {
            val yDivs = ArrayList<Div>()
            var tmpDiv: Div? = null
            for (i in 1..<bitmap.height) {
                tmpDiv = processChunk(bitmap[column, i], tmpDiv, i - 1, yDivs)
            }
            return yDivs
        }

        private fun getXDivs(bitmap: Bitmap, raw: Int): ArrayList<Div> {
            val xDivs = ArrayList<Div>()
            var tmpDiv: Div? = null
            for (i in 1..<bitmap.width) {
                tmpDiv = processChunk(bitmap[i, raw], tmpDiv, i - 1, xDivs)
            }
            return xDivs
        }

        private fun processChunk(
            pixel: Int,
            div: Div?,
            position: Int,
            divs: ArrayList<Div>,
        ): Div? {
            var tmpDiv = div
            if (isBlack(pixel)) {
                if (tmpDiv == null) {
                    tmpDiv = Div()
                    tmpDiv.start = position
                }
            }
            if (isTransparent(pixel)) {
                if (tmpDiv != null) {
                    tmpDiv.stop = position
                    divs.add(tmpDiv)
                    tmpDiv = null
                }
            }
            return tmpDiv
        }
    }
}
