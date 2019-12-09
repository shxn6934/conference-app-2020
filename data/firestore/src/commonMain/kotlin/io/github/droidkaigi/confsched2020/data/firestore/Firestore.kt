package io.github.droidkaigi.confsched2020.data.firestore

import io.github.droidkaigi.confsched2020.model.SessionId
import kotlinx.coroutines.flow.Flow

interface Firestore {
    fun getFavoriteSessionIds(): Flow<List<String>>
    suspend fun toggleFavorite(sessionId: SessionId)
}
