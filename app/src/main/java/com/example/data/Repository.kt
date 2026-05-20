package com.example.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

class AppRepository(context: Context) {

    private val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "royal_win_db"
    )
    .fallbackToDestructiveMigration() // safe for testing and upgrades
    .build()

    private val userDao = database.userDao()
    private val depositDao = database.depositDao()
    private val settingDao = database.settingDao()
    private val chatDao = database.chatDao()

    // --- User flow ---
    val allUsers: Flow<List<User>> = userDao.getAllUsersFlow().flowOn(Dispatchers.IO)
    
    fun getUserFlow(phone: String): Flow<User?> = userDao.getUserByPhoneFlow(phone).flowOn(Dispatchers.IO)

    suspend fun getUserByPhone(phone: String): User? = withContext(Dispatchers.IO) {
        userDao.getUserByPhone(phone)
    }

    suspend fun saveUser(user: User) = withContext(Dispatchers.IO) {
        userDao.insertOrUpdateUser(user)
    }

    suspend fun updateBalanceDirectly(phone: String, newBalance: Double) = withContext(Dispatchers.IO) {
        userDao.updateBalance(phone, newBalance)
    }

    suspend fun updateOverrideRtp(phone: String, rtpMode: String) = withContext(Dispatchers.IO) {
        userDao.updateOverrideRtp(phone, rtpMode)
    }

    suspend fun deleteUser(phone: String) = withContext(Dispatchers.IO) {
        userDao.deleteUser(phone)
    }

    // --- Deposits & Admin Approve flow ---
    val allDeposits: Flow<List<DepositTransaction>> = depositDao.getAllDepositsFlow().flowOn(Dispatchers.IO)

    fun getDepositsForUser(phone: String): Flow<List<DepositTransaction>> = 
        depositDao.getDepositsByPhoneFlow(phone).flowOn(Dispatchers.IO)

    suspend fun submitDepositRequest(phone: String, txId: String, amount: Double, activeWallet: String) = withContext(Dispatchers.IO) {
        val deposit = DepositTransaction(
            userPhoneNumber = phone,
            transactionId = txId,
            amount = amount,
            status = "PENDING",
            timestamp = System.currentTimeMillis(),
            walletNumber = activeWallet
        )
        depositDao.insertDeposit(deposit)
    }

    suspend fun approveDeposit(depositId: Long): Boolean = withContext(Dispatchers.IO) {
        val deposit = depositDao.getDepositById(depositId)
        if (deposit != null && deposit.status == "PENDING") {
            // Mark deposit as APPROVED
            depositDao.updateStatus(depositId, "APPROVED")
            
            // Increment remote balance
            val user = userDao.getUserByPhone(deposit.userPhoneNumber)
            if (user != null) {
                userDao.updateBalance(deposit.userPhoneNumber, user.balance + deposit.amount)
            }
            true
        } else {
            false
        }
    }

    suspend fun rejectDeposit(depositId: Long): Boolean = withContext(Dispatchers.IO) {
        val deposit = depositDao.getDepositById(depositId)
        if (deposit != null && deposit.status == "PENDING") {
            depositDao.updateStatus(depositId, "REJECTED")
            true
        } else {
            false
        }
    }

    // --- Dynamic Payment Configurator / Settings ---
    fun getPaymentWalletFlow(): Flow<String> = settingDao.getSettingFlow("payment_wallet_number")
        .map { it?.value ?: "0790123456" }
        .flowOn(Dispatchers.IO)

    suspend fun getPaymentWallet(): String = withContext(Dispatchers.IO) {
        settingDao.getSetting("payment_wallet_number")?.value ?: "0790123456"
    }

    suspend fun updatePaymentWallet(newNumber: String) = withContext(Dispatchers.IO) {
        settingDao.saveSetting(SystemSetting("payment_wallet_number", newNumber))
    }

    fun getGlobalRtpFlow(): Flow<String> = settingDao.getSettingFlow("global_rtp_mode")
        .map { it?.value ?: "NORMAL" }
        .flowOn(Dispatchers.IO)

    suspend fun getGlobalRtp(): String = withContext(Dispatchers.IO) {
        settingDao.getSetting("global_rtp_mode")?.value ?: "NORMAL"
    }

    suspend fun updateGlobalRtp(newMode: String) = withContext(Dispatchers.IO) {
        settingDao.saveSetting(SystemSetting("global_rtp_mode", newMode))
    }

    // --- Secure Session Management ---
    suspend fun storeSessionToken(phone: String, token: String) = withContext(Dispatchers.IO) {
        settingDao.saveSetting(SystemSetting("session_token_$phone", token))
    }

    suspend fun validateSessionToken(phone: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val storedSetting = settingDao.getSetting("session_token_$phone")
        storedSetting != null && storedSetting.value.isNotEmpty() && storedSetting.value == token
    }

    suspend fun clearSessionToken(phone: String) = withContext(Dispatchers.IO) {
        settingDao.saveSetting(SystemSetting("session_token_$phone", ""))
    }

    // Seeding dummy accounts if empty for demo
    suspend fun checkAndSeedDatabase() = withContext(Dispatchers.IO) {
        val existingUsers = userDao.getUserByPhone("admin123")
        if (existingUsers == null) {
            // Seed Admin
            userDao.insertOrUpdateUser(User(
                phoneNumber = "99999",
                password = "admin",
                balance = 1000000.0,
                isAdmin = true
            ))
            // Seed Demo User
            userDao.insertOrUpdateUser(User(
                phoneNumber = "12345678",
                password = "pass",
                balance = 500.0,
                isAdmin = false
            ))
            // Save initial wallet settings
            settingDao.saveSetting(SystemSetting("payment_wallet_number", "0770778899"))
            settingDao.saveSetting(SystemSetting("global_rtp_mode", "NORMAL"))
        }
    }

    // --- SLOT GAME CORE ENGINE WITH BEHAVIOR-BASED RTP ---
    
    // Available symbols and their multiplier rewards
    val symbols_3reel = listOf("7️⃣", "👑", "💎", "🔔", "🍒")
    val symbols_5reel = listOf("7️⃣", "👑", "💎", "🔔", "🍒", "🍇", "🍋")

    data class SpinResult(
        val reels: List<String>,
        val wonAmount: Double,
        val isWin: Boolean,
        val winDescription: String,
        val updatedBalance: Double,
        val targetRtpUsed: String,
        val behaviorApplied: String
    )

    suspend fun triggerSpin(
        phone: String,
        betAmount: Double,
        isFiveReelMode: Boolean
    ): SpinResult = withContext(Dispatchers.IO) {
        val user = userDao.getUserByPhone(phone) ?: throw Exception("User not found")
        val activeGlobalRtp = getGlobalRtp() // "NORMAL", "ALWAYS_WIN", "ALWAYS_LOSE", "FAVOR_PLAYER", "FAVOR_HOUSE"
        
        if (user.balance < betAmount) {
            throw Exception("Not enough balance! Please deposit to recharge.")
        }

        // Deduct bet amount atomically
        val balanceWithBetDeducted = user.balance - betAmount
        userDao.updateBalance(phone, balanceWithBetDeducted)

        // Determine target winning probability (RTP factor)
        // Default base hit rates:
        // 3-reel matches everything: Base Win chance ~ 25% (smaller rewards for pairs / match descriptions)
        // 5-reel matches layout: Win chance ~ 30% for partial matches
        
        var baseWinProbability = if (isFiveReelMode) 0.35 else 0.28
        var effectiveRtpCategory = "DEFAULT"
        var behaviorReport = "RTP Standard Base Rate"

        // 1. Check Admin settings first (explicit overrides)
        val userOverride = user.overrideRtp // "DEFAULT", "ALWAYS_WIN", "ALWAYS_LOSE", "HIGH_WIN_PROB", "LOW_WIN_PROB"
        
        val activeRtpSetting = if (userOverride != "DEFAULT") userOverride else activeGlobalRtp

        when (activeRtpSetting) {
            "ALWAYS_WIN" -> {
                baseWinProbability = 1.0
                effectiveRtpCategory = "FORCED WIN"
                behaviorReport = "Admin Forced 100% Win"
            }
            "ALWAYS_LOSE" -> {
                baseWinProbability = 0.0
                effectiveRtpCategory = "FORCED LOSE"
                behaviorReport = "Admin Forced 0% Loss"
            }
            "FAVOR_PLAYER", "HIGH_WIN_PROB" -> {
                baseWinProbability += 0.25
                effectiveRtpCategory = "FAVOR PLAYER"
                behaviorReport = "Boosted Probability for Player Advantage"
            }
            "FAVOR_HOUSE", "LOW_WIN_PROB" -> {
                baseWinProbability -= 0.15
                effectiveRtpCategory = "FAVOR HOUSE"
                behaviorReport = "Tightened House Edge to Protect Bank"
            }
            else -> {
                // Determine behavior-based RTP adaptiveness via iChancy AI VEHPS (VIP-Engagement & House Profitability Shield)
                val totalSpins = user.totalSpins
                val totalWon = user.totalWonAmount
                val totalBet = user.totalBetAmount
                val consecutiveLosses = user.consecutiveLosses
                val netEarnings = totalWon - totalBet

                var aiCalculatedModifier = 0.0
                val descriptionParts = mutableListOf<String>()

                // AI Pillar A: Dynamic Retention Rescue Net (scales up to +35% win rate when on losing streak)
                if (consecutiveLosses >= 2) {
                    val scaleFactor = (consecutiveLosses * 0.06).coerceAtMost(0.35)
                    aiCalculatedModifier += scaleFactor
                    descriptionParts.add("Rescue Net (+${String.format("%.0f%%", scaleFactor * 100)})")
                }

                // AI Pillar B: Profit-Guard Margin Protector (curbs win rates to secure house profitability on high lifetime wins)
                if (totalBet > 50.0) {
                    val winToBetRatio = totalWon / totalBet
                    if (winToBetRatio >= 1.6) {
                        val nerfFactor = -0.12
                        aiCalculatedModifier += nerfFactor
                        descriptionParts.add("Profit Shield (-12%)")
                    } else if (winToBetRatio <= 0.45 && netEarnings < -80.0) {
                        val boostFactor = 0.14
                        aiCalculatedModifier += boostFactor
                        descriptionParts.add("Engagement Boost (+14%)")
                    }
                }

                // AI Pillar C: High Frequency Loyalty Tweak (rewards loyal veteran sessions with mild math incentives)
                if (totalSpins >= 25) {
                    val loyaltyReward = 0.05
                    aiCalculatedModifier += loyaltyReward
                    descriptionParts.add("Loyalty (+5%)")
                }

                // AI Pillar D: Volatility Bet-Size Spike Regulator (mitigates double-down/high-roller single spin jackpot drains)
                if (totalSpins >= 4) {
                    val averageBet = totalBet / totalSpins
                    if (betAmount >= averageBet * 2.5) {
                        val spikeNerf = -0.15
                        aiCalculatedModifier += spikeNerf
                        descriptionParts.add("Sizing Guard (-15%)")
                    }
                }

                // Apply combining metrics to obtain overall winning probability
                baseWinProbability += aiCalculatedModifier
                effectiveRtpCategory = "AI_VEHPS_ADAPTIVE"

                val algorithmInsights = if (descriptionParts.isNotEmpty()) {
                    descriptionParts.joinToString(" | ")
                } else {
                    "Optimized Balanced Mode (Active)"
                }

                behaviorReport = "AI VEHPS Engine: $algorithmInsights"
            }
        }

        // Clamp winning probability between 0 and 1
        baseWinProbability = baseWinProbability.coerceIn(0.01, 1.00)

        // Spin Reels!
        val symbolsList = if (isFiveReelMode) symbols_5reel else symbols_3reel
        val reelCount = if (isFiveReelMode) 5 else 3
        
        var reels = List(reelCount) { symbolsList.random() }
        
        // Let's decide if this spin is mathematically selected to be a WIN or a LOSS
        val shouldWin = Random.nextDouble() < baseWinProbability
        
        if (shouldWin) {
            // Generate a winning reel combination
            if (isFiveReelMode) {
                // Win pattern: Match 3 or more of any symbol
                val winningSymbol = symbolsList.random()
                val matchesCount = (3..5).random()
                val mutableReels = MutableList(5) { symbolsList.random() }
                // Settle matches
                val indices = (0..4).shuffled().take(matchesCount)
                indices.forEach { idx -> mutableReels[idx] = winningSymbol }
                reels = mutableReels
            } else {
                // 3-reel Match 2 or 3 of any symbol
                val winningSymbol = symbolsList.random()
                val matchesCount = if (Random.nextDouble() < 0.3) 3 else 2 // 3 of a kind is 30% of wins, 2 of a kind is 70%
                val mutableReels = MutableList(3) { symbolsList.random() }
                
                if (matchesCount == 3) {
                    reels = List(3) { winningSymbol }
                } else {
                    val firstIndex = (0..2).random()
                    var secondIndex = (0..2).random()
                    while (secondIndex == firstIndex) {
                        secondIndex = (0..2).random()
                    }
                    mutableReels[firstIndex] = winningSymbol
                    mutableReels[secondIndex] = winningSymbol
                    
                    // Make sure the third symbol is different so it is not 3-of-a-kind
                    var thirdSymbol = symbolsList.random()
                    while (thirdSymbol == winningSymbol) {
                        thirdSymbol = symbolsList.random()
                    }
                    val thirdIndex = 3 - firstIndex - secondIndex
                    mutableReels[thirdIndex] = thirdSymbol
                    reels = mutableReels
                }
            }
        } else {
            // Generate a losing combination (Guaranteed no full matches / payout pairs)
            var attempts = 0
            while (attempts < 50) {
                val tempReels = List(reelCount) { symbolsList.random() }
                val hasMatch = if (isFiveReelMode) {
                    // count duplicates
                    val occurrences = tempReels.groupingBy { it }.eachCount()
                    occurrences.values.any { it >= 3 }
                } else {
                    // standard match-2 or match-3 count
                    tempReels[0] == tempReels[1] || tempReels[1] == tempReels[2] || tempReels[0] == tempReels[2]
                }
                
                if (!hasMatch) {
                    reels = tempReels
                    break
                }
                attempts++
            }
        }

        // Calculate Payout details
        var wonAmount = 0.0
        var winDescription = "No Win"
        var isWin = false

        if (isFiveReelMode) {
            val occurrences = reels.groupingBy { it }.eachCount()
            val maxMatchCount = occurrences.values.maxOrNull() ?: 1
            val maxMatchSymbol = occurrences.filterValues { it == maxMatchCount }.keys.firstOrNull() ?: "🍒"

            if (maxMatchCount >= 3) {
                isWin = true
                val multiplier = when (maxMatchSymbol) {
                    "7️⃣" -> if (maxMatchCount == 5) 25.0 else if (maxMatchCount == 4) 10.0 else 4.0
                    "👑" -> if (maxMatchCount == 5) 15.0 else if (maxMatchCount == 4) 6.0 else 3.0
                    "💎" -> if (maxMatchCount == 5) 12.0 else if (maxMatchCount == 4) 5.0 else 2.5
                    "🔔" -> if (maxMatchCount == 5) 10.0 else if (maxMatchCount == 4) 4.0 else 2.0
                    else -> if (maxMatchCount == 5) 6.0 else if (maxMatchCount == 4) 3.0 else 1.5 // fruits (🍒, 🍇, 🍋)
                }
                wonAmount = betAmount * multiplier
                winDescription = "$maxMatchCount-in-a-Row $maxMatchSymbol (${multiplier}x bet)!"
            }
        } else {
            // 3-reel payout
            if (reels[0] == reels[1] && reels[1] == reels[2]) {
                // Triple match!
                isWin = true
                val symbol = reels[0]
                val multiplier = when (symbol) {
                    "7️⃣" -> 30.0
                    "👑" -> 15.0
                    "💎" -> 10.0
                    "🔔" -> 7.0
                    else -> 4.0 // 🍒
                }
                wonAmount = betAmount * multiplier
                winDescription = "🔥 TRIPLE SEVEN $symbol - ${multiplier}x Bet! 🔥"
            } else {
                // Check double match
                val doubleMatchSymbol = when {
                    reels[0] == reels[1] -> reels[0]
                    reels[1] == reels[2] -> reels[1]
                    reels[0] == reels[2] -> reels[0]
                    else -> null
                }
                
                if (doubleMatchSymbol != null) {
                    isWin = true
                    val multiplier = when (doubleMatchSymbol) {
                        "7️⃣" -> 3.5
                        "👑" -> 2.5
                        "💎" -> 2.0
                        "🔔" -> 1.5
                        else -> 1.0 // 🍒
                    }
                    wonAmount = betAmount * multiplier
                    winDescription = "Double $doubleMatchSymbol Pair (${multiplier}x bet)!"
                }
            }
        }

        // Apply payouts to balance and update stats
        val finalBalanceUpdate = balanceWithBetDeducted + wonAmount
        
        // Update user record safely in db
        val updatedUser = user.copy(
            balance = finalBalanceUpdate,
            consecutiveLosses = if (isWin) 0 else user.consecutiveLosses + 1,
            totalSpins = user.totalSpins + 1,
            totalWonAmount = user.totalWonAmount + wonAmount,
            totalBetAmount = user.totalBetAmount + betAmount
        )
        userDao.insertOrUpdateUser(updatedUser)

        SpinResult(
            reels = reels,
            wonAmount = wonAmount,
            isWin = isWin,
            winDescription = winDescription,
            updatedBalance = finalBalanceUpdate,
            targetRtpUsed = String.format("%.0f%%", baseWinProbability * 100),
            behaviorApplied = behaviorReport
        )
    }

    // --- SUPPORT CHAT MANAGEMENT ---
    fun getMessagesForUser(userPhone: String): Flow<List<ChatMessage>> =
        chatDao.getMessagesForUserFlow(userPhone).flowOn(Dispatchers.IO)

    fun getActiveChatUsers(): Flow<List<String>> =
        chatDao.getActiveChatUsersFlow().flowOn(Dispatchers.IO)

    suspend fun sendChatMessage(userPhone: String, senderPhone: String, messageText: String) = withContext(Dispatchers.IO) {
        // Simple automatic censor check for toxic words/scam allegations
        val toxicKeywords = listOf("scam", "cheat", "hack", "fake", "stole", "steal", "fraud", "scammer", "hacker", "النصب", "نصاب", "احتيال", "غش")
        var shouldAutoCensor = false
        val lowerMsg = messageText.lowercase()
        for (word in toxicKeywords) {
            if (lowerMsg.contains(word)) {
                shouldAutoCensor = true
                break
            }
        }

        val msg = ChatMessage(
            userPhoneNumber = userPhone,
            senderPhoneNumber = senderPhone,
            message = messageText,
            timestamp = System.currentTimeMillis(),
            isCensored = shouldAutoCensor
        )
        chatDao.insertMessage(msg)
    }

    suspend fun toggleCensorMessage(messageId: Long, censored: Boolean) = withContext(Dispatchers.IO) {
        chatDao.updateCensored(messageId, censored)
    }

    suspend fun deleteChatMessage(messageId: Long) = withContext(Dispatchers.IO) {
        chatDao.deleteMessage(messageId)
    }

    suspend fun clearChatHistory(userPhone: String) = withContext(Dispatchers.IO) {
        chatDao.clearChatForUser(userPhone)
    }
}
