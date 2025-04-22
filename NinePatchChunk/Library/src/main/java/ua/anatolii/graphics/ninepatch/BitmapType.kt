
package ua.anatolii.graphics.ninepatch

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.NinePatchDrawable
import androidx.core.graphics.scale
import java.util.ArrayList

/**
 * Created by Anatolii on 11/2/13.
 */
enum class BitmapType {
    NinePatch {
        override fun createChunk(bitmap: Bitmap): NinePatchChunk {
            val chunk = bitmap.ninePatchChunk
            return if (chunk == null) {
                NinePatchChunk.createEmptyChunk()
            } else {
                NinePatchChunk.parse(chunk)
            }
        }
    },
    RawNinePatch {
        override fun createChunk(bitmap: Bitmap): NinePatchChunk {
            return try {
                NinePatchChunk.createChunkFromRawBitmap(bitmap, false)
            } catch (e: WrongPaddingException) {
                NinePatchChunk.createEmptyChunk()
            } catch (e: DivLengthException) {
                NinePatchChunk.createEmptyChunk()
            }
        }

        override fun modifyBitmap(
            resources: Resources,
            bitmap: Bitmap,
            chunk: NinePatchChunk,
        ): Bitmap {
            var content = Bitmap.createBitmap(bitmap, 1, 1, bitmap.width - 2, bitmap.height - 2)
            val targetDensity = resources.displayMetrics.densityDpi
            val densityChange = targetDensity.toFloat() / bitmap.density
            if (densityChange != 1f) {
                val dstWidth = Math.round(content.width * densityChange)
                val dstHeight = Math.round(content.height * densityChange)
                content = content.scale(dstWidth, dstHeight)
                content.density = targetDensity
                chunk.padding = Rect(
                    Math.round(chunk.padding.left * densityChange),
                    Math.round(chunk.padding.top * densityChange),
                    Math.round(chunk.padding.right * densityChange),
                    Math.round(chunk.padding.bottom * densityChange)
                )

                recalculateDivs(densityChange, chunk.xDivs)
                recalculateDivs(densityChange, chunk.yDivs)
            }
            return content
        }

        private fun recalculateDivs(densityChange: Float, divs: ArrayList<Div>) {
            for (div in divs) {
                div.start = Math.round(div.start * densityChange)
                div.stop = Math.round(div.stop * densityChange)
            }
        }
    },
    PlainImage, NULL {
        override fun createNinePatchDrawable(
            resources: Resources,
            bitmap: Bitmap,
            srcName: String?,
            extraPadding: Int,
        ): NinePatchDrawable? {
            return null
        }
    };

    /**
     * Depending on bitmap will return chunk which satisfies it.
     *
     * @param bitmap source image
     * @return chunk instance. Or EmptyChunk. Can't be null.
     */
    open fun createChunk(bitmap: Bitmap): NinePatchChunk {
        return NinePatchChunk.createEmptyChunk()
    }

    /**
     * Modifies source bitmap so it fits NinePatchDrawable requirements. Can change provided chunk.
     *
     * @param resources uses to get some information about system, get access to resources cache.
     * @param bitmap    source bitmap
     * @param chunk     chunk instance which was created using this bitmap.
     * @return modified bitmap or the same bitmap.
     */
    open fun modifyBitmap(resources: Resources, bitmap: Bitmap, chunk: NinePatchChunk): Bitmap {
        return bitmap
    }

    protected open fun createNinePatchDrawable(
        resources: Resources,
        bitmap: Bitmap,
        srcName: String?,
        extraPadding: Int,
    ): NinePatchDrawable? {
        val chunk = createChunk(bitmap)
        val padding = if (extraPadding == 0) {
            chunk.padding
        } else {
            Rect(
                chunk.padding.left + extraPadding,
                chunk.padding.top + extraPadding,
                chunk.padding.right + extraPadding,
                chunk.padding.bottom + extraPadding
            )
        }
        return NinePatchDrawable(
            resources,
            modifyBitmap(resources, bitmap, chunk),
            chunk.toBytes(),
            padding,
            srcName
        )
    }

    companion object {
        /**
         * Detects which type of bitmap source instance belongs.
         *
         * @param bitmap source image.
         * @return detected type of source image.
         */
        fun determineBitmapType(bitmap: Bitmap?): BitmapType {
            if (bitmap == null) return NULL
            val ninePatchChunk = bitmap.ninePatchChunk
            if (ninePatchChunk != null && android.graphics.NinePatch.isNinePatchChunk(ninePatchChunk)) return NinePatch
            if (NinePatchChunk.isRawNinePatchBitmap(bitmap)) return RawNinePatch
            return PlainImage
        }

        /**
         * Creates NinePatchDrawable instance from given bitmap.
         *
         * @param bitmap  source bitmap.
         * @param srcName The name of the source for the bitmap. Might be null.
         * @return not null NinePatchDrawable instance.
         */
        fun getNinePatchDrawable(
            resources: Resources,
            bitmap: Bitmap,
            srcName: String?,
            extraPadding: Int,
        ): NinePatchDrawable? {
            return determineBitmapType(bitmap).createNinePatchDrawable(resources, bitmap, srcName, extraPadding)
        }
    }
}
