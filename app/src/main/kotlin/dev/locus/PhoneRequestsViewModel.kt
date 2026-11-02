package dev.locus

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.device.PhoneRequests
import javax.inject.Inject

/** Hands the screen the phone integration tools' requests (MainActivity's PhoneRequestHost). */
@HiltViewModel
class PhoneRequestsViewModel @Inject constructor(val requests: PhoneRequests) : ViewModel()
