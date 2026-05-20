package com.example.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppRepository
import com.example.data.DepositTransaction
import com.example.data.User
import com.example.data.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application)
    private val sharedPrefs: SharedPreferences = application.getSharedPreferences("ichancy_secure_session", Context.MODE_PRIVATE)

    // --- Sound Effects Player ---
    private val soundEffectsPlayer = SoundEffectsPlayer()
    val isSoundEnabled = MutableStateFlow(sharedPrefs.getBoolean("is_sound_enabled", true))

    fun toggleSound() {
        val nextState = !isSoundEnabled.value
        isSoundEnabled.value = nextState
        sharedPrefs.edit().putBoolean("is_sound_enabled", nextState).apply()
        if (nextState) {
            playClick()
        }
    }

    fun playClick() {
        if (isSoundEnabled.value) {
            soundEffectsPlayer.playClick()
        }
    }

    fun playSpinTick() {
        if (isSoundEnabled.value) {
            soundEffectsPlayer.playSpinTick()
        }
    }

    fun playWin() {
        if (isSoundEnabled.value) {
            soundEffectsPlayer.playWin()
        }
    }

    // --- Dynamic Notifications ---
    private val _inAppNotification = MutableStateFlow<String?>(null)
    val inAppNotification: StateFlow<String?> = _inAppNotification.asStateFlow()

    fun dismissNotification() {
        _inAppNotification.value = null
    }

    // --- Current UI Screen ---
    // Screens: LOGIN, REGISTRATION, SLOT_GAME, DEPOSIT_WALLET, ADMIN_PANEL
    private val _currentScreen = MutableStateFlow("LOGIN")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    // --- Authentication States ---
    val authPhone = MutableStateFlow("")
    val authPassword = MutableStateFlow("")
    val isRegistering = MutableStateFlow(false)
    
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    // --- Slot Machine State ---
    val betAmount = MutableStateFlow(10.0)
    val isFiveReelMode = MutableStateFlow(false)
    
    private val _reelsState = MutableStateFlow(listOf("7️⃣", "7️⃣", "7️⃣"))
    val reelsState: StateFlow<List<String>> = _reelsState.asStateFlow()

    private val _isSpinning = MutableStateFlow(false)
    val isSpinning: StateFlow<Boolean> = _isSpinning.asStateFlow()

    private val _lastSpinResult = MutableStateFlow<String?>(null)
    val lastSpinResult: StateFlow<String?> = _lastSpinResult.asStateFlow()

    private val _lastWinAmountStr = MutableStateFlow<String?>(null)
    val lastWinAmountStr: StateFlow<String?> = _lastWinAmountStr.asStateFlow()

    private val _rtpHouseLog = MutableStateFlow<String?>(null)
    val rtpHouseLog: StateFlow<String?> = _rtpHouseLog.asStateFlow()

    private val _gameError = MutableStateFlow<String?>(null)
    val gameError: StateFlow<String?> = _gameError.asStateFlow()

    // --- Deposit States ---
    val depositAmountInput = MutableStateFlow("")
    val depositTransactionIdInput = MutableStateFlow("")
    
    private val _paymentWalletNumber = MutableStateFlow("0770778899")
    val paymentWalletNumber: StateFlow<String> = _paymentWalletNumber.asStateFlow()

    private val _depositMessage = MutableStateFlow<String?>(null)
    val depositMessage: StateFlow<String?> = _depositMessage.asStateFlow()

    // List of deposits for current user
    val userDeposits: StateFlow<List<DepositTransaction>> = _currentUser
        .filterNotNull()
        .flatMapLatest { user -> repository.getDepositsForUser(user.phoneNumber) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Admin Panel States ---
    val allUsers: StateFlow<List<User>> = repository.allUsers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDeposits: StateFlow<List<DepositTransaction>> = repository.allDeposits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val globalRtpMode: StateFlow<String> = repository.getGlobalRtpFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "NORMAL")

    // Admin configuration form
    val adminConfigWalletNumber = MutableStateFlow("")
    val adminManualPhoneToModify = MutableStateFlow("")
    val adminManualAmountToModify = MutableStateFlow("")

    init {
        viewModelScope.launch {
            repository.checkAndSeedDatabase()
            // Observe wallet address
            repository.getPaymentWalletFlow().collect {
                _paymentWalletNumber.value = it
                adminConfigWalletNumber.value = it
            }
        }
        
        // Observe current active user changes reactively
        viewModelScope.launch {
            _currentUser.collectLatest { user ->
                if (user != null) {
                    repository.getUserFlow(user.phoneNumber).collect { updatedUser ->
                        _currentUser.value = updatedUser
                    }
                }
            }
        }

        // Dynamically resize reels state when mode toggles
        viewModelScope.launch {
            isFiveReelMode.collect { is5 ->
                _reelsState.value = if (is5) listOf("7️⃣", "7️⃣", "7️⃣", "7️⃣", "7️⃣") else listOf("7️⃣", "7️⃣", "7️⃣")
            }
        }

        // Trigger auto-login lookup on application boot by validating storage session credentials
        viewModelScope.launch {
            val savedPhone = sharedPrefs.getString("session_phone", null)
            val savedToken = sharedPrefs.getString("session_token", null)
            if (!savedPhone.isNullOrEmpty() && !savedToken.isNullOrEmpty()) {
                val isValid = repository.validateSessionToken(savedPhone, savedToken)
                if (isValid) {
                    val user = repository.getUserByPhone(savedPhone)
                    if (user != null && !user.isBlocked) {
                        _currentUser.value = user
                        if (user.isAdmin) {
                            setScreen("ADMIN_PANEL")
                        } else {
                            setScreen("SLOT_GAME")
                        }
                    }
                }
            }
        }

        // Monitor pending deposits reactively and send elegant VIP status popups when edited by admin
        viewModelScope.launch {
            var cachedStatuses = mapOf<Long, String>()
            userDeposits.collect { deposits ->
                if (cachedStatuses.isNotEmpty()) {
                    depsLoop@ for (dep in deposits) {
                        val oldStatus = cachedStatuses[dep.id]
                        if (oldStatus != null && oldStatus != dep.status) {
                            if (dep.status == "APPROVED") {
                                _inAppNotification.value = "🎉 تم قبول طلب شحن الرصيد بقيمة " + String.format("%,.0f", dep.amount) + " NSP بنجاح! تم الشحن الآن."
                                break@depsLoop
                            } else if (dep.status == "REJECTED") {
                                _inAppNotification.value = "⚠️ تم رفض طلب عملية الإيداع للرقم المرجعي (ID): " + dep.transactionId
                                break@depsLoop
                            }
                        }
                    }
                }
                cachedStatuses = deposits.associate { it.id to it.status }
            }
        }
    }

    fun setScreen(screen: String) {
        _currentScreen.value = screen
        _authError.value = null
        _depositMessage.value = null
        _gameError.value = null
    }

    // --- Authentication Operations ---
    fun handleAuthentication() {
        val phone = authPhone.value.trim()
        val password = authPassword.value.trim()

        if (phone.isEmpty() || password.isEmpty()) {
            _authError.value = "Please fill in all phone and password fields."
            return
        }

        viewModelScope.launch {
            val existingUser = repository.getUserByPhone(phone)
            
            if (isRegistering.value) {
                // Register process
                if (existingUser != null) {
                    _authError.value = "Phone number is already registered!"
                } else {
                    val newUser = User(
                        phoneNumber = phone,
                        password = password,
                        balance = 500.0, // Give some starter credits to test
                        isAdmin = false
                    )
                    repository.saveUser(newUser)
                    
                    // Generate secure token and persist Client-Server session
                    val token = UUID.randomUUID().toString()
                    repository.storeSessionToken(phone, token)
                    sharedPrefs.edit()
                        .putString("session_phone", phone)
                        .putString("session_token", token)
                        .apply()

                    _currentUser.value = newUser
                    setScreen("SLOT_GAME")
                }
            } else {
                // Login process
                if (existingUser == null) {
                    _authError.value = "Phone number not registered!"
                } else if (existingUser.password != password) {
                    _authError.value = "Incorrect password! Please try again."
                } else if (existingUser.isBlocked) {
                    _authError.value = "This account is blocked by the administrator."
                } else {
                    // Generate secure token and persist Client-Server session
                    val token = UUID.randomUUID().toString()
                    repository.storeSessionToken(phone, token)
                    sharedPrefs.edit()
                        .putString("session_phone", phone)
                        .putString("session_token", token)
                        .apply()

                    _currentUser.value = existingUser
                    if (existingUser.isAdmin) {
                        setScreen("ADMIN_PANEL")
                    } else {
                        setScreen("SLOT_GAME")
                    }
                }
            }
        }
    }

    fun performFastDemoLogin(isPlayer: Boolean) {
        viewModelScope.launch {
            if (isPlayer) {
                val demoPhone = "12345678"
                var demoUser = repository.getUserByPhone(demoPhone)
                if (demoUser == null) {
                    demoUser = User(phoneNumber = demoPhone, password = "pass", balance = 500.0)
                    repository.saveUser(demoUser)
                }
                
                // Keep logged in via secure token
                val token = UUID.randomUUID().toString()
                repository.storeSessionToken(demoPhone, token)
                sharedPrefs.edit()
                    .putString("session_phone", demoPhone)
                    .putString("session_token", token)
                    .apply()

                _currentUser.value = demoUser
                setScreen("SLOT_GAME")
            } else {
                val adminPhone = "99999"
                var adminUser = repository.getUserByPhone(adminPhone)
                if (adminUser == null) {
                    adminUser = User(phoneNumber = adminPhone, password = "admin", balance = 1000000.0, isAdmin = true)
                    repository.saveUser(adminUser)
                }

                // Keep logged in via secure token
                val token = UUID.randomUUID().toString()
                repository.storeSessionToken(adminPhone, token)
                sharedPrefs.edit()
                    .putString("session_phone", adminPhone)
                    .putString("session_token", token)
                    .apply()

                _currentUser.value = adminUser
                setScreen("ADMIN_PANEL")
            }
        }
    }

    fun logout() {
        val user = _currentUser.value
        if (user != null) {
            viewModelScope.launch {
                repository.clearSessionToken(user.phoneNumber)
            }
        }
        // Purge Client Session State SharedPreferences
        sharedPrefs.edit()
            .remove("session_phone")
            .remove("session_token")
            .apply()

        _currentUser.value = null
        authPhone.value = ""
        authPassword.value = ""
        setScreen("LOGIN")
    }

    // --- Gameplay Spin Operations ---
    fun handleSpin() {
        val user = _currentUser.value ?: return
        val bet = betAmount.value
        val is5 = isFiveReelMode.value

        if (_isSpinning.value) return

        if (user.balance < bet) {
            _gameError.value = "Insufficient balance! Please submit a deposit."
            return
        }

        _gameError.value = null
        _isSpinning.value = true
        _lastSpinResult.value = null
        _lastWinAmountStr.value = null
        _rtpHouseLog.value = null

        viewModelScope.launch {
            val reelCount = if (is5) 5 else 3
            val finalSymbolsList = if (is5) repository.symbols_5reel else repository.symbols_3reel

            // 1. Run Spinning Animation loop for realistic feel
            // Incrementally changes the state with minor delays
            for (i in 0..12) {
                _reelsState.value = List(reelCount) { finalSymbolsList.random() }
                playSpinTick()
                delay(70 + (i * 10).toLong()) // exponential brake effect
            }

            // 2. Query final deterministic calculated result from the behavior-based engine
            try {
                val result = repository.triggerSpin(user.phoneNumber, bet, is5)
                
                // Set reels to exact final symbols
                _reelsState.value = result.reels
                
                // Display win outputs
                if (result.isWin) {
                    _lastSpinResult.value = result.winDescription
                    _lastWinAmountStr.value = "+$" + String.format("%.2f", result.wonAmount)
                    playWin()
                } else {
                    _lastSpinResult.value = "Try Again!"
                    _lastWinAmountStr.value = null
                    playClick()
                }
                
                // Log the behavior action details (visible to user/admin for interactive audit)
                _rtpHouseLog.value = "[RTP: ${result.targetRtpUsed}] ${result.behaviorApplied}"
                
            } catch (e: Exception) {
                _gameError.value = e.message ?: "An unexpected gaming error occurred."
            } finally {
                _isSpinning.value = false
            }
        }
    }

    // --- Wallet / User Deposit Submission ---
    fun submitWalletDeposit() {
        val user = _currentUser.value ?: return
        val amount = depositAmountInput.value.toDoubleOrNull()
        val txId = depositTransactionIdInput.value.trim()
        val activeWalletNum = _paymentWalletNumber.value

        if (amount == null || amount <= 0.0) {
            _depositMessage.value = "Error: Please enter a valid amount greater than 0."
            return
        }
        if (txId.isEmpty()) {
            _depositMessage.value = "Error: Please paste your unique transaction / reference ID."
            return
        }

        viewModelScope.launch {
            repository.submitDepositRequest(user.phoneNumber, txId, amount, activeWalletNum)
            depositAmountInput.value = ""
            depositTransactionIdInput.value = ""
            _depositMessage.value = "Success! Transaction submitted for verification. Balance will update once approved by admin."
        }
    }

    // --- Admin Operations ---
    
    // Save payment phone address
    fun adminSaveWalletConfig() {
        val num = adminConfigWalletNumber.value.trim()
        if (num.isNotEmpty()) {
            viewModelScope.launch {
                repository.updatePaymentWallet(num)
            }
        }
    }

    // Modify User Balance Directly
    fun adminModifyUserBalance(isAdd: Boolean) {
        val phone = adminManualPhoneToModify.value.trim()
        val amountStr = adminManualAmountToModify.value.trim()
        val amount = amountStr.toDoubleOrNull()

        if (phone.isEmpty() || amount == null || amount <= 0) {
            return
        }

        viewModelScope.launch {
            val user = repository.getUserByPhone(phone)
            if (user != null) {
                val currentBal = user.balance
                val newBal = if (isAdd) currentBal + amount else max(0.0, currentBal - amount)
                repository.updateBalanceDirectly(phone, newBal)
                
                adminManualPhoneToModify.value = ""
                adminManualAmountToModify.value = ""
            }
        }
    }

    // Quick fill phone for modification from list
    fun adminSelectUserToModify(phone: String) {
        adminManualPhoneToModify.value = phone
    }

    // Approve/Reject User Transaction ID
    fun adminProcessTransaction(id: Long, isApprove: Boolean) {
        viewModelScope.launch {
            if (isApprove) {
                repository.approveDeposit(id)
            } else {
                repository.rejectDeposit(id)
            }
        }
    }

    // Change global behavior override speed
    fun adminChangeGlobalRtp(mode: String) {
        viewModelScope.launch {
            repository.updateGlobalRtp(mode)
        }
    }

    // Force user specific RTP override setting
    fun adminSetUserRtpOverride(phone: String, mode: String) {
        viewModelScope.launch {
            repository.updateOverrideRtp(phone, mode)
        }
    }

    // Block or Unblock user
    fun adminToggleUserBlock(user: User) {
        viewModelScope.launch {
            val updatedUser = user.copy(isBlocked = !user.isBlocked)
            repository.saveUser(updatedUser)
        }
    }

    // --- Support Chat Feature ---
    val chatInputText = MutableStateFlow("")

    val currentUserChatMessages: StateFlow<List<ChatMessage>> = _currentUser
        .filterNotNull()
        .flatMapLatest { user -> repository.getMessagesForUser(user.phoneNumber) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Admin support chat lists
    val activeChatUsers: StateFlow<List<String>> = repository.getActiveChatUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val adminSelectedChatUser = MutableStateFlow<String?>(null)
    val adminChatInputText = MutableStateFlow("")

    val adminSelectedChatMessages: StateFlow<List<ChatMessage>> = adminSelectedChatUser
        .flatMapLatest { phone ->
            if (phone != null) {
                repository.getMessagesForUser(phone)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Actions
    fun sendUserChatMessage() {
        val user = _currentUser.value ?: return
        val msgText = chatInputText.value.trim()
        if (msgText.isEmpty()) return

        viewModelScope.launch {
            repository.sendChatMessage(user.phoneNumber, user.phoneNumber, msgText)
            chatInputText.value = ""
        }
    }

    fun sendAdminChatMessage() {
        val adminUser = _currentUser.value ?: return
        if (!adminUser.isAdmin) return
        val targetUser = adminSelectedChatUser.value ?: return
        val msgText = adminChatInputText.value.trim()
        if (msgText.isEmpty()) return

        viewModelScope.launch {
            repository.sendChatMessage(targetUser, "admin", msgText)
            adminChatInputText.value = ""
        }
    }

    // Toggle message censorship
    fun adminToggleCensorMessage(message: ChatMessage) {
        viewModelScope.launch {
            repository.toggleCensorMessage(message.id, !message.isCensored)
        }
    }

    // Delete a specific message
    fun adminDeleteMessage(messageId: Long) {
        viewModelScope.launch {
            repository.deleteChatMessage(messageId)
        }
    }

    // Clear whole chat history
    fun adminClearChatHistory(userPhone: String) {
        viewModelScope.launch {
            repository.clearChatHistory(userPhone)
            if (adminSelectedChatUser.value == userPhone) {
                adminSelectedChatUser.value = null
            }
        }
    }

    fun selectAdminChatUser(phone: String?) {
        adminSelectedChatUser.value = phone
    }
}
