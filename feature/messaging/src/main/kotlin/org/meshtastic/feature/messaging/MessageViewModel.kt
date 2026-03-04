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

import android.content.Context
import android.os.RemoteException
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicReference

import ai.moonshine.voice.MicTranscriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptEventListener
import ai.moonshine.voice.JNI

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

    private val contactKeyForPagedMessages: MutableStateFlow<String?> = MutableStateFlow(null)
    private val pagedMessagesForContactKey: Flow<PagingData<Message>> =
        contactKeyForPagedMessages
            .filterNotNull()
            .flatMapLatest { contactKey -> packetRepository.getMessagesFromPaged(contactKey, ::getNode) }
            .cachedIn(viewModelScope)

    fun setTitle(title: String) {
        viewModelScope.launch { _title.value = title }
    }

    fun getMessagesFromPaged(contactKey: String): Flow<PagingData<Message>> {
        contactKeyForPagedMessages.value = contactKey
        return pagedMessagesForContactKey
    }

    fun getFirstUnreadMessageUuid(contactKey: String): Flow<Long?> =
        packetRepository.getFirstUnreadMessageUuid(contactKey)

    fun hasUnreadMessages(contactKey: String): Flow<Boolean> = packetRepository.hasUnreadMessages(contactKey)

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

    // ---------- MOONSHINE VOICE ----------

    private var transcriber: MicTranscriber? = null
    private var speechResultCallback: ((String?) -> Unit)? = null
    private var speechErrorCallback: (() -> Unit)? = null
    private val accumulatedTranscript = java.lang.StringBuffer()
    private val currentLine = AtomicReference("")

    private val _isVoiceModelLoaded = MutableStateFlow(false)
    val isVoiceModelLoaded: StateFlow<Boolean> = _isVoiceModelLoaded

    fun initTranscriber(activity: AppCompatActivity) {
        if (transcriber != null) {
            Timber.d("Moonshine: transcriber already initialized")
            return
        }
        Timber.d("Moonshine: initializing MicTranscriber with activity: $activity")
        try {
            val newTranscriber = MicTranscriber(activity)
            transcriber = newTranscriber
            
            viewModelScope.launch {
                try {
                    Timber.d("Moonshine: starting model load")
                    withContext(Dispatchers.IO) {
                        newTranscriber.loadFromAssets(activity, "base-en", JNI.MOONSHINE_MODEL_ARCH_BASE)
                    }
                    _isVoiceModelLoaded.value = true
                    Timber.d("Moonshine: model loaded successfully")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Voice model loaded", Toast.LENGTH_SHORT).show()
                    }
                    // Notify permission if already granted
                    newTranscriber.onMicPermissionGranted()
                } catch (e: Exception) {
                    Timber.e(e, "Moonshine model load error")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Voice model load failed", Toast.LENGTH_LONG).show()
                    }
                }
            }

            newTranscriber.addListener { event ->
                event.accept(object : TranscriptEventListener() {
                    override fun onLineStarted(e: TranscriptEvent.LineStarted) {
                        Timber.d("Moonshine: line started")
                    }
                    override fun onLineTextChanged(e: TranscriptEvent.LineTextChanged) {
                        currentLine.set(e.line.text)
                        Timber.d("Moonshine: text changed: ${e.line.text}")
                    }
                    override fun onLineCompleted(e: TranscriptEvent.LineCompleted) {
                        val text = e.line.text
                        Timber.d("Moonshine line completed: $text")
                        if (!text.isNullOrBlank()) {
                            accumulatedTranscript.append(text).append(" ")
                        }
                        currentLine.set("")
                    }
                    override fun onError(e: TranscriptEvent.Error) {
                        Timber.e("Moonshine transcription error event: $e")
                    }
                })
            }
        } catch (e: Exception) {
            Timber.e(e, "Moonshine initialization error")
        }
    }

    fun startVoiceRecording(
        activity: AppCompatActivity,
        contactKey: String,
        onResult: (String?) -> Unit,
        onError: () -> Unit,
    ) {
        initTranscriber(activity)
        
        if (!_isVoiceModelLoaded.value) {
            Timber.w("Moonshine: model not loaded yet")
            Toast.makeText(appContext, "Voice model still loading...", Toast.LENGTH_SHORT).show()
            onError()
            return
        }

        // store callbacks so listener can use them
        speechResultCallback = onResult
        speechErrorCallback = onError
        accumulatedTranscript.setLength(0) // reset for new recording
        currentLine.set("")

        try {
            transcriber?.onMicPermissionGranted() // Important to notify transcriber
            transcriber?.start()
            Timber.d("Moonshine: started listening")
        } catch (e: Exception) {
            Timber.e(e, "Moonshine start error")
            Toast.makeText(appContext, "Recording failed to start", Toast.LENGTH_SHORT).show()
            onError()
        }
    }

    fun stopVoiceRecordingAndTranscribe() {
        Timber.d("Moonshine: stop requested")
        viewModelScope.launch {
            try {
                transcriber?.stop()
                // Small delay to allow last buffers to process from the listener
                kotlinx.coroutines.delay(800) 
                val result = (accumulatedTranscript.toString() + " " + currentLine.get()).trim()
                Timber.d("Moonshine: stopped listening. Final transcript: $result")
                
                withContext(Dispatchers.Main) {
                    if (result.isBlank()) {
                        Toast.makeText(appContext, "No speech detected", Toast.LENGTH_SHORT).show()
                    }
                    speechResultCallback?.invoke(result.ifBlank { null })
                }
            } catch (e: Exception) {
                Timber.e(e, "Moonshine stop error")
                withContext(Dispatchers.Main) {
                    speechResultCallback?.invoke(null)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        transcriber?.stop()
    }
}
