package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users")
data class User(
    @PrimaryKey val phoneNumber: String,
    val password: String,
    val balance: Double,
    val consecutiveLosses: Int = 0,
    val totalSpins: Int = 0,
    val totalWonAmount: Double = 0.0,
    val totalBetAmount: Double = 0.0,
    val overrideRtp: String = "DEFAULT", // "DEFAULT", "ALWAYS_WIN", "ALWAYS_LOSE", "FAVOR_PLAYER", "FAVOR_HOUSE"
    val isBlocked: Boolean = false,
    val isAdmin: Boolean = false
)

@Entity(tableName = "deposits")
data class DepositTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userPhoneNumber: String,
    val transactionId: String,
    val amount: Double,
    val status: String, // "PENDING", "APPROVED", "REJECTED"
    val timestamp: Long,
    val walletNumber: String
)

@Entity(tableName = "settings")
data class SystemSetting(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY phoneNumber ASC")
    fun getAllUsersFlow(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getUserByPhone(phoneNumber: String): User?

    @Query("SELECT * FROM users WHERE phoneNumber = :phoneNumber LIMIT 1")
    fun getUserByPhoneFlow(phoneNumber: String): Flow<User?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateUser(user: User)

    @Query("UPDATE users SET balance = :newBalance WHERE phoneNumber = :phoneNumber")
    suspend fun updateBalance(phoneNumber: String, newBalance: Double)

    @Query("UPDATE users SET overrideRtp = :rtpMode WHERE phoneNumber = :phoneNumber")
    suspend fun updateOverrideRtp(phoneNumber: String, rtpMode: String)

    @Query("DELETE FROM users WHERE phoneNumber = :phoneNumber")
    suspend fun deleteUser(phoneNumber: String)
}

@Dao
interface DepositDao {
    @Query("SELECT * FROM deposits ORDER BY timestamp DESC")
    fun getAllDepositsFlow(): Flow<List<DepositTransaction>>

    @Query("SELECT * FROM deposits WHERE userPhoneNumber = :phone ORDER BY timestamp DESC")
    fun getDepositsByPhoneFlow(phone: String): Flow<List<DepositTransaction>>

    @Query("SELECT * FROM deposits WHERE id = :id LIMIT 1")
    suspend fun getDepositById(id: Long): DepositTransaction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeposit(deposit: DepositTransaction)

    @Query("UPDATE deposits SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userPhoneNumber: String, // Chat thread owner
    val senderPhoneNumber: String, // "admin" or standard phone number
    val message: String,
    val timestamp: Long,
    val isCensored: Boolean = false
)

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): SystemSetting?

    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    fun getSettingFlow(key: String): Flow<SystemSetting?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSetting(setting: SystemSetting)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE userPhoneNumber = :userPhone ORDER BY timestamp ASC")
    fun getMessagesForUserFlow(userPhone: String): Flow<List<ChatMessage>>

    @Query("SELECT DISTINCT userPhoneNumber FROM chat_messages ORDER BY timestamp DESC")
    fun getActiveChatUsersFlow(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Query("UPDATE chat_messages SET isCensored = :censored WHERE id = :id")
    suspend fun updateCensored(id: Long, censored: Boolean)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM chat_messages WHERE userPhoneNumber = :userPhone")
    suspend fun clearChatForUser(userPhone: String)
}

@Database(entities = [User::class, DepositTransaction::class, SystemSetting::class, ChatMessage::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun depositDao(): DepositDao
    abstract fun settingDao(): SettingDao
    abstract fun chatDao(): ChatDao
}
