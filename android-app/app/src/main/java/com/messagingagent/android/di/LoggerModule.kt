package com.messagingagent.android.di

import com.messagingagent.android.service.DeviceLogger
import com.messagingagent.android.service.WebSocketRelayClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggerModule {

    @Binds
    @Singleton
    abstract fun bindDeviceLogger(impl: WebSocketRelayClient): DeviceLogger
}
