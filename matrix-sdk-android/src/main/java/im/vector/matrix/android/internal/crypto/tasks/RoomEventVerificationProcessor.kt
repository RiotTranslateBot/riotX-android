/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.crypto.tasks

import im.vector.matrix.android.api.session.crypto.sas.VerificationService
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.message.MessageContent
import im.vector.matrix.android.api.session.room.model.message.MessageRelationContent
import im.vector.matrix.android.api.session.room.model.message.MessageType
import im.vector.matrix.android.api.session.room.model.message.MessageVerificationReadyContent
import im.vector.matrix.android.api.session.room.model.message.MessageVerificationRequestContent
import im.vector.matrix.android.api.session.room.model.message.MessageVerificationStartContent
import im.vector.matrix.android.internal.crypto.verification.VerificationEventHandler
import im.vector.matrix.android.internal.di.DeviceId
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.session.sync.RoomEventsProcessor
import timber.log.Timber
import java.util.ArrayList
import javax.inject.Inject

internal class RoomEventVerificationProcessor @Inject constructor(
        @DeviceId private val deviceId: String?,
        @UserId private val userId: String,
        private val onVerificationEvent: VerificationEventHandler
) : RoomEventsProcessor {

    companion object {
        // XXX what about multi-account?
        private val transactionsHandledByOtherDevice = ArrayList<String>()

        private val ALLOWED_TYPES = listOf(
                EventType.KEY_VERIFICATION_START,
                EventType.KEY_VERIFICATION_ACCEPT,
                EventType.KEY_VERIFICATION_KEY,
                EventType.KEY_VERIFICATION_MAC,
                EventType.KEY_VERIFICATION_CANCEL,
                EventType.KEY_VERIFICATION_DONE,
                EventType.KEY_VERIFICATION_READY,
                EventType.MESSAGE
        )
    }

    override suspend fun process(mode: RoomEventsProcessor.Mode, roomId: String, events: List<Event>) {

        if (mode != RoomEventsProcessor.Mode.INCREMENTAL_SYNC) {
            // if it's a request automatically mark as outdated
            return
        }
        events.forEach { event ->

            Timber.d("## SAS Verification: received msgId: ${event.eventId} msgtype: ${event.type} from ${event.senderId}")
            Timber.v("## SAS Verification: received msgId: $event")

            // If the request is in the future by more than 5 minutes or more than 10 minutes in the past,
            // the message should be ignored by the receiver.

            if (!VerificationService.isValidRequest(event.ageLocalTs ?: event.originServerTs)) {
                return@forEach Unit.also {
                    Timber.d("## SAS Verification: msgId: ${event.eventId} is outdated")
                }
            }
            // Event should be decrypted at this point

            if (event.isEncrypted() && event.mxDecryptionResult == null) {
                // TODO use a global event decryptor? attache to session and that listen to new sessionId?
                Timber.w("## SAS Ignoring potential event, cannot decrypt event: ${event.eventId}")
                return@forEach
            }

            val clearType = event.getClearType()
            if (clearType == EventType.MESSAGE
                    && MessageType.MSGTYPE_VERIFICATION_REQUEST != event.getClearContent().toModel<MessageContent>()?.msgType) {
                Timber.d("## SAS Verification: discard msgId: ${event.eventId}, not interested")
                return@forEach
            }

            if (!ALLOWED_TYPES.contains(clearType)) {
                Timber.d("## SAS Verification: discard msgId: ${event.eventId}, not interested")
                return@forEach
            }

            Timber.v("## SAS Verification: received msgId: ${event.eventId} type: $clearType")

            if (event.senderId == userId) {
                // If it's send from me, we need to keep track of Requests or Start
                // done from another device of mine

                if (EventType.MESSAGE == event.type) {
                    event.getClearContent().toModel<MessageVerificationRequestContent>()?.let {
                        if (it.fromDevice != deviceId) {
                            // The verification is requested from another device
                            Timber.v("## SAS Verification: Transaction requested from other device  tid:${event.eventId} ")
                            event.eventId?.let { txId -> transactionsHandledByOtherDevice.add(txId) }
                        }
                    }
                } else if (EventType.KEY_VERIFICATION_START == event.type) {
                    event.getClearContent().toModel<MessageVerificationStartContent>()?.let {
                        if (it.fromDevice != deviceId) {
                            // The verification is started from another device
                            Timber.v("## SAS Verification : Transaction started by other device  tid:${it.transactionID} ")
                            it.transactionID?.let { txId -> transactionsHandledByOtherDevice.add(txId) }
                            onVerificationEvent.onRoomRequestHandledByOtherDevice(event)
                        }
                    }
                } else if (EventType.KEY_VERIFICATION_READY == event.type) {
                    event.getClearContent().toModel<MessageVerificationReadyContent>()?.let {
                        if (it.fromDevice != deviceId) {
                            // The verification is started from another device
                            Timber.v("## SAS Verification : Transaction started by other device  tid:${it.transactionID} ")
                            it.transactionID?.let { txId -> transactionsHandledByOtherDevice.add(txId) }
                            onVerificationEvent.onRoomRequestHandledByOtherDevice(event)
                        }
                    }
                } else if (EventType.KEY_VERIFICATION_CANCEL == event.type || EventType.KEY_VERIFICATION_DONE == event.type) {
                    event.getClearContent().toModel<MessageRelationContent>()?.relatesTo?.eventId?.let {
                        transactionsHandledByOtherDevice.remove(it)
                        onVerificationEvent.onRoomRequestHandledByOtherDevice(event)
                    }
                }

                Timber.v("## SAS Verification ignoring message sent by me: ${event.eventId} type: $clearType")
                return@forEach
            }

            val relatesTo = event.getClearContent().toModel<MessageRelationContent>()?.relatesTo?.eventId
            if (relatesTo != null && transactionsHandledByOtherDevice.contains(relatesTo)) {
                // Ignore this event, it is directed to another of my devices
                Timber.v("## SAS Verification : Ignore Transaction handled by other device  tid:$relatesTo ")
                return@forEach
            }
            onVerificationEvent.onRoomEvent(event)
        }
    }
}
