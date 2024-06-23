package net.ballmerlabs.uscatterbrain.network.desktop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.wifidirect.PortSocket
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.lang.IllegalArgumentException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

@DesktopApiScope
class NsdAdvertiserImpl @Inject constructor(
    private val context: Context,
    private val serviceConfig: DesktopApiSubcomponent.ServiceConfig,
    private val portSocket: PortSocket,
    private val manager: NsdManager,
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) val timeoutScheduler: Scheduler
) : NsdAdvertiser {

    private val LOG by scatterLog()

    private val advertiseObs = PublishSubject.create<Maybe<NsdServiceInfo>>()

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {
            LOG.e("onRegistrationFailed $p1")
            advertiseObs.onNext(Maybe.error(IllegalStateException("failed $p1")))
        }

        override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {
            LOG.w("onUnregistrationFailed: $p1")
            advertiseObs.onNext(Maybe.error(IllegalStateException("failed $p1")))
        }

        override fun onServiceRegistered(p0: NsdServiceInfo?) {
            LOG.w("onServiceRegistered: ${p0?.serviceName} ${p0?.serviceType}")

            if (p0 != null) {
                advertiseObs.onNext(Maybe.just(p0))
            } else  {
                advertiseObs.onNext(Maybe.empty())
            }
        }

        override fun onServiceUnregistered(p0: NsdServiceInfo?) {
            advertiseObs.onNext(Maybe.empty())
        }

    }

    private val serviceInfo = NsdServiceInfo().apply {
        serviceType = SERVICE
        serviceName = serviceConfig.name
        port = portSocket.socket.localPort
    }

    override fun startAdvertise(): Completable {
        return advertiseObs
            .mergeWith(Completable.fromAction {
                manager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    registrationListener
                )
            })
            .flatMapMaybe { v ->
                v
            }.firstOrError()
            .timeout(5, TimeUnit.SECONDS, timeoutScheduler)
            .ignoreElement()
    }

    override fun stopAdvertise() {
        try {
            manager.unregisterService(registrationListener)
        } catch (exc: IllegalArgumentException) {
            LOG.w("attempted to unregister service without listener registered: $exc")
        }
    }

    companion object {
        const val SERVICE = "_sbd._tcp"
    }
}