package com.scrcpyandroid.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.scrcpyandroid.protocol.MirrorOptions
import com.scrcpyandroid.session.MirrorSessionService
import com.scrcpyandroid.session.SessionState

object Routes {
    const val Connect = "connect"
    const val Settings = "settings"
    const val Mirror = "mirror"
}

@Composable
fun ScrcpyAppNav(
    sessionState: SessionState,
    initialOptions: MirrorOptions,
    service: MirrorSessionService?,
    onSaveOptions: (MirrorOptions) -> Unit,
    onStart: (MirrorOptions) -> Unit,
    onStop: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val navController = rememberNavController()
    val start = when (sessionState) {
        is SessionState.Streaming, is SessionState.Connecting, is SessionState.Reconnecting -> Routes.Mirror
        else -> Routes.Connect
    }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.Connect) {
            ConnectScreen(
                initialOptions = initialOptions,
                sessionState = sessionState,
                onStart = { opts ->
                    onStart(opts)
                    navController.navigate(Routes.Mirror) {
                        launchSingleTop = true
                    }
                },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenBatterySettings = onOpenBatterySettings,
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                initialOptions = initialOptions,
                onSave = {
                    onSaveOptions(it)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.Mirror) {
            MirrorScreen(
                sessionState = sessionState,
                service = service,
                onStop = {
                    onStop()
                    navController.navigate(Routes.Connect) {
                        popUpTo(Routes.Connect) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBackToConnect = {
                    navController.navigate(Routes.Connect) {
                        popUpTo(Routes.Connect) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
