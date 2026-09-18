package com.stb6.spdf.ui.reader

import com.stb6.spdf.ui.UiText

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class PasswordRequired(
        val retry: Boolean,
        val verifying: Boolean = false,
    ) : ReaderUiState

    data class Error(val reason: ErrorReason, val detail: UiText? = null) : ReaderUiState

    data class Ready(
        val pageCount: Int,
        val documentIsDark: Boolean,
        val token: Long,
    ) : ReaderUiState
}

enum class ErrorReason {
    CANNOT_OPEN,

    UNAVAILABLE,
}
