package com.buckcast.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.buckcast.data.entitlement.BillingGateway
import com.buckcast.data.entitlement.EntitlementRepositoryImpl
import com.buckcast.data.entitlement.RemoteCustomerEntitlement
import com.buckcast.data.local.BuckCastDatabase
import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.model.AppRuntimeConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntitlementRepositoryTest {

    private lateinit var db: BuckCastDatabase
    private var clockMs = 1_795_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BuckCastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `first launch starts 36h preview, expires cleanly at 36h, supports VIP access code, and enforces debug flag`() =
        runTest {
            val fakeGateway = object : BillingGateway {
                var promoForCode: String? = null
                override fun configureIfReady() = Unit
                override fun currentAppUserIdOrNull(): String? = null
                override suspend fun fetchCustomerEntitlement(): Result<RemoteCustomerEntitlement?> =
                    Result.success(null)
                override suspend fun restorePurchases(): Result<RemoteCustomerEntitlement?> =
                    Result.success(null)
                override suspend fun purchaseAlpha(isAnnual: Boolean): Result<RemoteCustomerEntitlement?> =
                    Result.success(null)
                override suspend fun logInAccessCode(code: String): Result<RemoteCustomerEntitlement?> {
                    promoForCode = code
                    return Result.success(
                        RemoteCustomerEntitlement(
                            appUserId = code,
                            serverFirstSeenEpochMs = clockMs,
                            isAlphaActive = true,
                            source = EntitlementSource.PROMO_COMP,
                            expiresAtEpochMs = clockMs + 30L * 86_400_000L,
                        ),
                    )
                }
            }

            val debugRepo = EntitlementRepositoryImpl(
                cacheDao = db.entitlementCacheDao(),
                billingGateway = fakeGateway,
                runtimeConfig = AppRuntimeConfig(isDebugBuild = true, revenueCatPublicKey = "", mapTilerApiKey = ""),
                clockMs = { clockMs },
            )

            debugRepo.ensureInitialized()
            val initial = debugRepo.observeEntitlement().first()
            assertEquals(SubscriptionTier.ALPHA, initial.tier)
            assertEquals(EntitlementSource.ALPHA_PREVIEW_36H, initial.source)
            assertTrue(initial.isPreviewActive)

            // Advance 37 hours -> expires to SCOUT
            clockMs += 37L * 3_600_000L
            val expired = debugRepo.observeEntitlement().first()
            assertEquals(SubscriptionTier.SCOUT, expired.tier)
            assertFalse(expired.isPreviewActive)

            // Debug override toggles back to ALPHA in debug builds
            debugRepo.setDebugOverride(DebugEntitlementOverride.FORCE_ALPHA)
            assertEquals(SubscriptionTier.ALPHA, debugRepo.observeEntitlement().first().tier)
            debugRepo.setDebugOverride(DebugEntitlementOverride.AUTO)
            assertEquals(SubscriptionTier.SCOUT, debugRepo.observeEntitlement().first().tier)

            // VIP / Promo Access code activates ALPHA PROMO_COMP
            val vipResult = debugRepo.logInWithAccessCode("DROPTINE-VIP-2026")
            assertTrue(vipResult.isSuccess)
            assertEquals(EntitlementSource.PROMO_COMP, vipResult.getOrNull()?.source)
            assertEquals("DROPTINE-VIP-2026", debugRepo.getSupportUserId())
        }
}
