/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.meshtastic.feature.messaging

import android.os.RemoteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.meshtastic.core.data.repository.NodeRepository
import org.meshtastic.core.data.repository.PacketRepository
import org.meshtastic.core.data.repository.QuickChatActionRepository
import org.meshtastic.core.data.repository.RadioConfigRepository
import org.meshtastic.core.database.entity.ContactSettings
import org.meshtastic.core.database.model.Message
import org.meshtastic.core.database.model.Node
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.DeviceVersion
import org.meshtastic.core.prefs.ui.UiPrefs
import org.meshtastic.core.service.MeshServiceNotifications
import org.meshtastic.core.service.ServiceAction
import org.meshtastic.core.service.ServiceRepository
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig.Role
import org.meshtastic.proto.channelSet
import org.meshtastic.proto.sharedContact
import timber.log.Timber
import javax.inject.Inject

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext

private const val VERIFIED_CONTACT_FIRMWARE_CUTOFF = "2.7.12"

@HiltViewModel
class MessageViewModel
@Inject
constructor(
    private val nodeRepository: NodeRepository,
    radioConfigRepository: RadioConfigRepository,
    quickChatActionRepository: QuickChatActionRepository,
    private val serviceRepository: ServiceRepository,
    private val packetRepository: PacketRepository,
    private val uiPrefs: UiPrefs,
    private val meshServiceNotifications: MeshServiceNotifications,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    val ourNodeInfo = nodeRepository.ourNodeInfo

    val connectionState = serviceRepository.connectionState

    val nodeList: StateFlow<List<Node>> = nodeRepository.getNodes().stateInWhileSubscribed(initialValue = emptyList())

    val channels = radioConfigRepository.channelSetFlow.stateInWhileSubscribed(channelSet {})

    private val _showQuickChat = MutableStateFlow(uiPrefs.showQuickChat)
    val showQuickChat: StateFlow<Boolean> = _showQuickChat

    val quickChatActions = quickChatActionRepository.getAllActions().stateInWhileSubscribed(initialValue = emptyList())

    val contactSettings: StateFlow<Map<String, ContactSettings>> =
        packetRepository.getContactSettings().stateInWhileSubscribed(initialValue = emptyMap())

    private val contactKeyForMessages: MutableStateFlow<String?> = MutableStateFlow(null)
    private val messagesForContactKey: StateFlow<List<Message>> =
        contactKeyForMessages
            .filterNotNull()
            .flatMapLatest { contactKey -> packetRepository.getMessagesFrom(contactKey, ::getNode) }
            .stateInWhileSubscribed(initialValue = emptyList())

    fun setTitle(title: String) {
        viewModelScope.launch { _title.value = title }
    }

    fun getMessagesFrom(contactKey: String): StateFlow<List<Message>> {
        contactKeyForMessages.value = contactKey
        return messagesForContactKey
    }

    fun toggleShowQuickChat() = toggle(_showQuickChat) { uiPrefs.showQuickChat = it }

    private fun toggle(state: MutableStateFlow<Boolean>, onChanged: (newValue: Boolean) -> Unit) {
        (!state.value).let { toggled ->
            state.update { toggled }
            onChanged(toggled)
        }
    }

    fun getNode(userId: String?) = nodeRepository.getNode(userId ?: DataPacket.ID_BROADCAST)

    fun getUser(userId: String?) = nodeRepository.getUser(userId ?: DataPacket.ID_BROADCAST)

    /**
     * Sends a message to a contact or channel.
     *
     * If the message is a direct message (no channel specified), this function will:
     * - If the device firmware version is older than 2.7.12, it will mark the destination node as a favorite to prevent
     *   it from being removed from the on-device node database.
     * - If the device firmware version is 2.7.12 or newer, it will send a shared contact to the destination node.
     *
     * @param str The message content.
     * @param contactKey The unique contact key, which is a combination of channel (optional) and node ID. Defaults to
     *   broadcasting on channel 0.
     * @param replyId The ID of the message this is a reply to, if any.
     */
    @Suppress("NestedBlockDepth")
    fun sendMessage(str: String, contactKey: String = "0${DataPacket.ID_BROADCAST}", replyId: Int? = null) {
        // contactKey: unique contact key filter (channel)+(nodeId)
        val channel = contactKey[0].digitToIntOrNull()
        val dest = if (channel != null) contactKey.substring(1) else contactKey

        // if the destination is a node, we need to ensure it's a
        // favorite so it does not get removed from the on-device node database.
        if (channel == null) { // no channel specified, so we assume it's a direct message
            val fwVersion = ourNodeInfo.value?.metadata?.firmwareVersion
            val destNode = nodeRepository.getNode(dest)
            val isClientBase = ourNodeInfo.value?.user?.role == Role.CLIENT_BASE
            fwVersion?.let { fw ->
                val ver = DeviceVersion(asString = fw)
                val verifiedSharedContactsVersion =
                    DeviceVersion(
                        asString = VERIFIED_CONTACT_FIRMWARE_CUTOFF,
                    ) // Version cutover to verified shared contacts

                if (ver >= verifiedSharedContactsVersion) {
                    sendSharedContact(destNode)
                } else {
                    if (!destNode.isFavorite && !isClientBase) {
                        favoriteNode(destNode)
                    }
                }
            }
        }
        val p = DataPacket(dest, channel ?: 0, str, replyId)
        sendDataPacket(p)
    }

    fun sendReaction(emoji: String, replyId: Int, contactKey: String) =
        viewModelScope.launch { serviceRepository.onServiceAction(ServiceAction.Reaction(emoji, replyId, contactKey)) }

    fun deleteMessages(uuidList: List<Long>) =
        viewModelScope.launch(Dispatchers.IO) { packetRepository.deleteMessages(uuidList) }

    fun clearUnreadCount(contact: String, messageUuid: Long, lastReadTimestamp: Long) =
        viewModelScope.launch(Dispatchers.IO) {
            val existingTimestamp = contactSettings.value[contact]?.lastReadMessageTimestamp ?: Long.MIN_VALUE
            if (lastReadTimestamp <= existingTimestamp) {
                return@launch
            }
            packetRepository.clearUnreadCount(contact, lastReadTimestamp)
            packetRepository.updateLastReadMessage(contact, messageUuid, lastReadTimestamp)
            val unreadCount = packetRepository.getUnreadCount(contact)
            if (unreadCount == 0) meshServiceNotifications.cancelMessageNotification(contact)
        }

    private fun favoriteNode(node: Node) = viewModelScope.launch {
        try {
            serviceRepository.onServiceAction(ServiceAction.Favorite(node))
        } catch (ex: RemoteException) {
            Timber.e(ex, "Favorite node error")
        }
    }

    private fun sendSharedContact(node: Node) = viewModelScope.launch {
        try {
            val contact = sharedContact {
                nodeNum = node.num
                user = node.user
                manuallyVerified = node.manuallyVerified
            }
            serviceRepository.onServiceAction(ServiceAction.SendContact(contact = contact))
        } catch (ex: RemoteException) {
            Timber.e(ex, "Send shared contact error")
        }
    }

    private fun sendDataPacket(p: DataPacket) {
        try {
            serviceRepository.meshService?.send(p)
        } catch (ex: RemoteException) {
            Timber.e("Send DataPacket error: ${ex.message}")
        }
    }
    // ---------- VOICE RECOGNITION ----------

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechResultCallback: ((String?) -> Unit)? = null
    private var speechErrorCallback: (() -> Unit)? = null

    fun startVoiceRecording(
        contactKey: String, // kept for symmetry; not used
        onResult: (String?) -> Unit,
        onError: () -> Unit,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Timber.w("Speech recognition not available")
            onError()
            return
        }

        // store callbacks so listener can use them
        speechResultCallback = onResult
        speechErrorCallback = onError

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(object : RecognitionListener {

                    override fun onResults(results: Bundle) {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        Timber.d("Speech result: $text")
                        speechResultCallback?.invoke(text)
                    }

                    override fun onError(error: Int) {
                        Timber.w("Speech recognizer error: $error")
                        speechErrorCallback?.invoke()
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onReadyForSpeech(params: Bundle?) {
                        Timber.d("Speech: ready for speech")
                    }

                    override fun onBeginningOfSpeech() {
                        Timber.d("Speech: beginning of speech")
                    }

                    override fun onEndOfSpeech() {
                        Timber.d("Speech: end of speech")
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        Timber.d("Speech: partial results received")
                    }

                })
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }

        // start listening as soon as user taps mic
        speechRecognizer?.startListening(intent)
    }

    fun stopVoiceRecordingAndTranscribe() {
        // stopping will trigger onResults / onError using the callbacks set above
        speechRecognizer?.stopListening()
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
