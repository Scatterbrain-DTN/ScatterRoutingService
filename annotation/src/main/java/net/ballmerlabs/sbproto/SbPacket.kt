package net.ballmerlabs.sbproto

import proto.Scatterbrain.MessageType

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SbPacket(val messageType: MessageType)