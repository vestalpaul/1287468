package com.buckcast.data.entitlement

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.buckcast.data.local.dao.EntitlementCacheDao
import com.buckcast.data.local.entities.EntitlementCacheEntity
import com.buckcast.domain.entitlement.DebugEntitlementOverride
import com.buckcast.domain.entitlement.EntitlementEvaluator
import com.buckcast.domain.entitlement.EntitlementSnapshot
import com.buckcast.domain.entitlement.EntitlementSource
import com.buckcast.domain.entitlement.EntitlementState
import com.buckcast.domain.entitlement.SubscriptionTier
import com.buckcast.domain.model.AppRuntimeConfig
import com.buckcast.domain.repository.EntitlementRepository
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.interfaces.LogInCallback
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.models.StoreTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class RemoteCustomerEntitlement(
    val appUserId: String,
    val serverFirstSeenEpochMs: Long?,
    val isAlphaActive: Boolean,
    val source: EntitlementSource,
    val expiresAtEpochMs: Long?,
)

interface BillingGateway {
    fun configureIfReady()
    fun currentAppUserIdOrNull(): String?
    suspend fun fetchCustomerEntitlement(): Result<RemoteCustomerEntitlement?>
    suspend fun restorePurchases(): Result<RemoteCustomerEntitlement?>
    suspend fun logInAccessCode(code: String): Result<RemoteCustomerEntitlement?>
    suspend fun purchaseAlpha(isAnnual: Boolean): Result<RemoteCustomerEntitlement?>
}

/**
 * Production RevenueCat (`com.revenuecat.purchases:purchases`) adapter.
 * Operates safely in local/offline mode when placeholder keys are present in debug builds.
 */
class RevenueCatBillingGateway(
    private val context: Context,
    private val runtimeConfig: AppRuntimeConfig,
) : BillingGateway {

    private val configured = AtomicBoolean(false)
    private var currentActivityRef = WeakReference<Activity?>(null)

    private val isLiveKeyConfigured: Boolean
        get() = runtimeConfig.revenueCatPublicKey.isNotBlank() &&
            !runtimeConfig.revenueCatPublicKey.startsWith("goog_placeholder")

    override fun configureIfReady() {
        if (!isLiveKeyConfigured) return
        if (configured.compareAndSet(false, true)) {
            val app = context.applicationContext as? Application
            app?.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        currentActivityRef = WeakReference(activity)
                    }

                    override fun onActivityPaused(activity: Activity) {
                        if (currentActivityRef.get() === activity) {
                            currentActivityRef = WeakReference(null)
                        }
                    }

                    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
                    override fun onActivityStarted(a: Activity) = Unit
                    override fun onActivityStopped(a: Activity) = Unit
                    override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
                    override fun onActivityDestroyed(a: Activity) = Unit
                },
            )
            Purchases.configure(
                PurchasesConfiguration.Builder(context, runtimeConfig.revenueCatPublicKey).build(),
            )
        }
    }

    override fun currentAppUserIdOrNull(): String? =
        if (configured.get()) Purchases.sharedInstance.appUserID else null

    override suspend fun fetchCustomerEntitlement(): Result<RemoteCustomerEntitlement?> {
        if (!isLiveKeyConfigured) return Result.success(null)
        configureIfReady()
        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.getCustomerInfo(
                object : ReceiveCustomerInfoCallback {
                    override fun onReceived(customerInfo: CustomerInfo) {
                        cont.resume(Result.success(customerInfo.toRemoteEntitlement()))
                    }

                    override fun onError(error: PurchasesError) {
                        cont.resume(Result.failure(IllegalStateException(error.message)))
                    }
                },
            )
        }
    }

    override suspend fun restorePurchases(): Result<RemoteCustomerEntitlement?> {
        if (!isLiveKeyConfigured) {
            return Result.failure(
                IllegalStateException("Google Play restore is available once the live RevenueCat key is configured."),
            )
        }
        configureIfReady()
        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.restorePurchases(
                object : ReceiveCustomerInfoCallback {
                    override fun onReceived(customerInfo: CustomerInfo) {
                        cont.resume(Result.success(customerInfo.toRemoteEntitlement()))
                    }

                    override fun onError(error: PurchasesError) {
                        cont.resume(Result.failure(IllegalStateException(error.message)))
                    }
                },
            )
        }
    }

    override suspend fun logInAccessCode(code: String): Result<RemoteCustomerEntitlement?> {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Access code cannot be blank."))
        }
        if (!isLiveKeyConfigured) {
            return Result.failure(
                IllegalStateException("Play Console / RevenueCat live key required for access codes. Use Debug Switcher in debug builds."),
            )
        }
        configureIfReady()
        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.logIn(
                trimmed,
                object : LogInCallback {
                    override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                        cont.resume(Result.success(customerInfo.toRemoteEntitlement()))
                    }

                    override fun onError(error: PurchasesError) {
                        cont.resume(Result.failure(IllegalStateException(error.message)))
                    }
                },
            )
        }
    }

    override suspend fun purchaseAlpha(isAnnual: Boolean): Result<RemoteCustomerEntitlement?> {
        if (!isLiveKeyConfigured) {
            return Result.failure(
                IllegalStateException("Subscriptions activate once Play Console SKUs are linked in release config."),
            )
        }
        configureIfReady()
        val activity = currentActivityRef.get()
            ?: return Result.failure(IllegalStateException("No foreground activity available for Play Billing."))

        val offeringsResult = suspendCancellableCoroutine<Result<Offerings>> { cont ->
            Purchases.sharedInstance.getOfferings(
                object : ReceiveOfferingsCallback {
                    override fun onReceived(offerings: Offerings) {
                        cont.resume(Result.success(offerings))
                    }

                    override fun onError(error: PurchasesError) {
                        cont.resume(Result.failure(IllegalStateException(error.message)))
                    }
                },
            )
        }
        val offerings = offeringsResult.getOrElse { return Result.failure(it) }
        val targetPackageType = if (isAnnual) PackageType.ANNUAL else PackageType.MONTHLY
        val pkg = offerings.current?.availablePackages?.firstOrNull { it.packageType == targetPackageType }
            ?: return Result.failure(IllegalStateException("DropTine Alpha offering package not found."))

        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.purchase(
                PurchaseParams.Builder(activity, pkg).build(),
                object : PurchaseCallback {
                    override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
                        cont.resume(Result.success(customerInfo.toRemoteEntitlement()))
                    }

                    override fun onError(error: PurchasesError, userCancelled: Boolean) {
                        val msg = if (userCancelled) "Purchase cancelled" else error.message
                        cont.resume(Result.failure(IllegalStateException(msg)))
                    }
                },
            )
        }
    }

    private fun CustomerInfo.toRemoteEntitlement(): RemoteCustomerEntitlement {
        val alpha = entitlements[ALPHA_ENTITLEMENT_ID]
        val isActive = alpha?.isActive == true
        val source = when {
            !isActive -> EntitlementSource.NONE
            alpha?.store == Store.PROMOTIONAL -> EntitlementSource.PROMO_COMP
            else -> EntitlementSource.PLAY_SUBSCRIPTION
        }
        return RemoteCustomerEntitlement(
            appUserId = originalAppUserId,
            serverFirstSeenEpochMs = firstSeen.time,
            isAlphaActive = isActive,
            source = source,
            expiresAtEpochMs = alpha?.expirationDate?.time,
        )
    }

    companion object {
        const val ALPHA_ENTITLEMENT_ID = "alpha"
    }
}

class EntitlementRepositoryImpl(
    private val cacheDao: EntitlementCacheDao,
    private val billingGateway: BillingGateway,
    private val runtimeConfig: AppRuntimeConfig,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : EntitlementRepository {

    private val mutex = Mutex()

    override fun observeEntitlement(): Flow<EntitlementState> =
        cacheDao.observeCache().map { entity ->
            val nowMs = clockMs()
            val resolved = entity ?: createInitialEntity(nowMs)
            evaluateEntity(resolved, nowMs)
        }

    override suspend fun ensureInitialized() {
        billingGateway.configureIfReady()
        mutex.withLock {
            val nowMs = clockMs()
            val existing = cacheDao.getCacheOnce()
            if (existing == null) {
                cacheDao.upsert(createInitialEntity(nowMs))
            } else {
                val updatedMax = maxOf(existing.maxObservedEpochMs, nowMs)
                val remoteId = billingGateway.currentAppUserIdOrNull()
                if (updatedMax != existing.maxObservedEpochMs || (remoteId != null && remoteId != existing.supportUserId)) {
                    cacheDao.upsert(
                        existing.copy(
                            maxObservedEpochMs = updatedMax,
                            supportUserId = remoteId ?: existing.supportUserId,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun refreshEntitlements(): Result<EntitlementState> =
        syncWithGateway { billingGateway.fetchCustomerEntitlement() }

    override suspend fun restorePurchases(): Result<EntitlementState> =
        syncWithGateway { billingGateway.restorePurchases() }

    override suspend fun logInWithAccessCode(code: String): Result<EntitlementState> =
        syncWithGateway { billingGateway.logInAccessCode(code) }

    override suspend fun purchaseAlpha(isAnnual: Boolean): Result<EntitlementState> =
        syncWithGateway { billingGateway.purchaseAlpha(isAnnual) }

    override suspend fun getSupportUserId(): String {
        ensureInitialized()
        return cacheDao.getCacheOnce()?.supportUserId
            ?: billingGateway.currentAppUserIdOrNull()
            ?: generateSupportId()
    }

    override suspend fun setDebugOverride(override: DebugEntitlementOverride) {
        if (!runtimeConfig.isDebugBuild) return
        ensureInitialized()
        mutex.withLock {
            val current = cacheDao.getCacheOnce() ?: createInitialEntity(clockMs())
            cacheDao.upsert(current.copy(debugOverride = override.name))
        }
    }

    override suspend fun reset36HourPreview() {
        if (!runtimeConfig.isDebugBuild) return
        mutex.withLock {
            val nowMs = clockMs()
            val current = cacheDao.getCacheOnce() ?: createInitialEntity(nowMs)
            cacheDao.upsert(
                current.copy(
                    localFirstLaunchEpochMs = nowMs,
                    serverFirstSeenEpochMs = null,
                    maxObservedEpochMs = nowMs,
                    debugOverride = DebugEntitlementOverride.AUTO.name,
                ),
            )
        }
    }

    private suspend fun syncWithGateway(
        block: suspend () -> Result<RemoteCustomerEntitlement?>,
    ): Result<EntitlementState> {
        ensureInitialized()
        val remoteResult = block()
        val nowMs = clockMs()
        if (remoteResult.isFailure) {
            return Result.failure(remoteResult.exceptionOrNull() ?: IllegalStateException("Sync failed"))
        }
        val remote = remoteResult.getOrNull()
        val updatedEntity = mutex.withLock {
            val current = cacheDao.getCacheOnce() ?: createInitialEntity(nowMs)
            val next = if (remote != null) {
                current.copy(
                    serverFirstSeenEpochMs = listOfNotNull(
                        current.serverFirstSeenEpochMs,
                        remote.serverFirstSeenEpochMs,
                    ).minOrNull(),
                    maxObservedEpochMs = maxOf(current.maxObservedEpochMs, nowMs),
                    lastKnownTier = if (remote.isAlphaActive) SubscriptionTier.ALPHA.name else SubscriptionTier.SCOUT.name,
                    lastKnownSource = remote.source.name,
                    subscriptionExpiresEpochMs = remote.expiresAtEpochMs,
                    lastVerifiedEpochMs = nowMs,
                    supportUserId = remote.appUserId.ifBlank { current.supportUserId },
                )
            } else {
                current.copy(maxObservedEpochMs = maxOf(current.maxObservedEpochMs, nowMs))
            }
            cacheDao.upsert(next)
            next
        }
        return Result.success(evaluateEntity(updatedEntity, nowMs))
    }

    private fun evaluateEntity(entity: EntitlementCacheEntity, nowMs: Long): EntitlementState {
        val snapshot = EntitlementSnapshot(
            localFirstLaunchEpochMs = entity.localFirstLaunchEpochMs,
            serverFirstSeenEpochMs = entity.serverFirstSeenEpochMs,
            maxObservedEpochMs = entity.maxObservedEpochMs,
            lastKnownTier = SubscriptionTier.entries.firstOrNull { it.name == entity.lastKnownTier }
                ?: SubscriptionTier.SCOUT,
            lastKnownSource = EntitlementSource.entries.firstOrNull { it.name == entity.lastKnownSource }
                ?: EntitlementSource.NONE,
            subscriptionExpiresEpochMs = entity.subscriptionExpiresEpochMs,
            lastVerifiedEpochMs = entity.lastVerifiedEpochMs,
            debugOverride = DebugEntitlementOverride.entries.firstOrNull { it.name == entity.debugOverride }
                ?: DebugEntitlementOverride.AUTO,
            supportUserId = entity.supportUserId,
        )
        return EntitlementEvaluator.evaluate(
            snapshot = snapshot,
            nowEpochMs = nowMs,
            allowDebugOverride = runtimeConfig.isDebugBuild,
        )
    }

    private fun createInitialEntity(nowMs: Long): EntitlementCacheEntity = EntitlementCacheEntity(
        id = EntitlementCacheEntity.SINGLETON_ID,
        localFirstLaunchEpochMs = nowMs,
        serverFirstSeenEpochMs = null,
        maxObservedEpochMs = nowMs,
        lastKnownTier = SubscriptionTier.SCOUT.name,
        lastKnownSource = EntitlementSource.NONE.name,
        subscriptionExpiresEpochMs = null,
        lastVerifiedEpochMs = null,
        debugOverride = DebugEntitlementOverride.AUTO.name,
        supportUserId = billingGateway.currentAppUserIdOrNull() ?: generateSupportId(),
    )

    private fun generateSupportId(): String =
        "dt_" + UUID.randomUUID().toString().replace("-", "").take(12)
}
