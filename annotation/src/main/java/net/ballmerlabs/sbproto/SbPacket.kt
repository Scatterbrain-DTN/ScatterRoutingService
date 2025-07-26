package net.ballmerlabs.sbproto

import scatterbrain.Scatterbrain.MessageType

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SbPacket(val messageType: MessageType)