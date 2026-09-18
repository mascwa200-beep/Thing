package dev.mascwa.pulse.device

import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size

/**
 * Bounds how many pixels any image may be decoded into, on the phone that is actually running.
 *
 * ## Why an interceptor rather than an argument at every `AsyncImage`
 *
 * There are ten `AsyncImage` call sites across the two applications and there will be more. A cap
 * passed at each one is a rule stated ten times, which is the duplicated-definition drift this
 * repository has corrected six times — and the eleventh call site would simply not have it. One
 * interceptor on the shared loader governs every request, including the ones nobody remembers, at
 * the cost of no call-site change at all.
 *
 * ## What it actually catches
 *
 * ⚠️ **Coil already bounds most decodes and this does not replace that.** `coil.compose`'s
 * `requestOfWithSizeResolver` installs a `ConstraintsSizeResolver` unless the request names its own
 * size or `contentScale` is `None` — read out of the shipped coil-compose 2.7.0 bytecode rather
 * than recalled — so an image inside a bounded box already decodes to roughly the box.
 *
 * What it does NOT bound is a dimension the layout left open, which arrives here as
 * [Dimension.Undefined] and means *decode at the source's full resolution*. A `fillMaxWidth()`
 * image inside a scrolling `Column` has exactly that shape: the width is the pane, the height is
 * unbounded, and a very tall photograph is then decoded whole. That is the genuinely unbounded
 * case, and on a cheap phone it is an out-of-memory rather than a slow frame.
 *
 * ⚠️ **This does change [DeviceClass.Tier.FULL] behaviour, and saying so beats pretending
 * otherwise.** [DeviceClass.budgetFor] promises the FULL row is today's behaviour exactly, and at
 * FULL the cap is 2048 px — above any phone screen, so nothing a person looks at is softer, but an
 * article image published at 4000 px is now decoded at 2048 where before it was decoded whole. The
 * cap is written to be generous rather than absent for precisely this reason.
 *
 * @param maxPx read per request, so a phone that gets hot decodes smaller from the next image on.
 *   Pass a cheap accessor — [DeviceProbeReader.budgetCached], never [DeviceProbeReader.budget],
 *   which makes binder calls and would make a scrolling list of thumbnails far worse than the
 *   decode ever was.
 */
class DecodeCapInterceptor(private val maxPx: () -> Int) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val cap = runCatching { maxPx() }.getOrNull()?.takeIf { it > 0 }
            ?: return chain.proceed(chain.request)

        val size = chain.size
        val capped = Size(capDimension(size.width, cap), capDimension(size.height, cap))
        // Identity, not merely equality of intent: an unchanged size must not mint a second chain,
        // because the size participates in the memory-cache key and a needless rebuild would be a
        // second cache entry for the same picture.
        if (capped == size) return chain.proceed(chain.request)
        return chain.withSize(capped).proceed(chain.request)
    }

    private fun capDimension(d: Dimension, cap: Int): Dimension = when (d) {
        // Already bounded by the layout. Lower it only if the layout asked for more than the phone
        // can afford; raising it would be this class making a weak phone worse.
        is Dimension.Pixels -> if (d.px > cap) Dimension.Pixels(cap) else d
        // Undefined = decode at source resolution. The unbounded case, and the reason this exists.
        else -> Dimension.Pixels(cap)
    }
}
