package com.myhealth.ai.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {

    val Mic: ImageVector by lazy {
        ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                lineTo(15f, 5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                lineTo(9f, 11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
                moveTo(17.3f, 11f)
                curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
                curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
                lineTo(5f, 11f)
                curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
                lineTo(11f, 21f)
                lineTo(13f, 21f)
                lineTo(13f, 17.72f)
                curveTo(16.28f, 17.23f, 19f, 14.41f, 19f, 11f)
                lineTo(17.3f, 11f)
                close()
            }
        }.build()
    }

    val Stop: ImageVector by lazy {
        ImageVector.Builder("Stop", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(6f, 6f)
                horizontalLineTo(18f)
                verticalLineTo(18f)
                horizontalLineTo(6f)
                close()
            }
        }.build()
    }

    val Warning: ImageVector by lazy {
        ImageVector.Builder("Warning", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(1f, 21f)
                horizontalLineTo(23f)
                lineTo(12f, 2f)
                lineTo(1f, 21f)
                close()
                moveTo(13f, 18f)
                horizontalLineTo(11f)
                verticalLineTo(16f)
                horizontalLineTo(13f)
                verticalLineTo(18f)
                close()
                moveTo(13f, 14f)
                horizontalLineTo(11f)
                verticalLineTo(10f)
                horizontalLineTo(13f)
                verticalLineTo(14f)
                close()
            }
        }.build()
    }

    val HeartPulse: ImageVector by lazy {
        ImageVector.Builder("HeartPulse", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(12f, 21.35f)
                lineTo(10.55f, 20.03f)
                curveTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
                curveTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
                curveTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f)
                curveTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
                curveTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
                curveTo(22f, 12.28f, 18.6f, 15.36f, 13.45f, 20.04f)
                lineTo(12f, 21.35f)
                close()
            }
        }.build()
    }

    val Timeline: ImageVector by lazy {
        ImageVector.Builder("Timeline", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(19f, 3f)
                horizontalLineTo(18f)
                verticalLineTo(1f)
                horizontalLineTo(16f)
                verticalLineTo(3f)
                horizontalLineTo(8f)
                verticalLineTo(1f)
                horizontalLineTo(6f)
                verticalLineTo(3f)
                horizontalLineTo(5f)
                curveTo(3.89f, 3f, 3f, 3.9f, 3f, 5f)
                verticalLineTo(19f)
                curveTo(3f, 20.1f, 3.89f, 21f, 5f, 21f)
                horizontalLineTo(19f)
                curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
                verticalLineTo(5f)
                curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
                close()
                moveTo(19f, 19f)
                horizontalLineTo(5f)
                verticalLineTo(8f)
                horizontalLineTo(19f)
                verticalLineTo(19f)
                close()
            }
        }.build()
    }

    val Chart: ImageVector by lazy {
        ImageVector.Builder("Chart", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(5f, 9.2f)
                horizontalLineTo(8f)
                verticalLineTo(19f)
                horizontalLineTo(5f)
                close()
                moveTo(10.6f, 5f)
                horizontalLineTo(13.4f)
                verticalLineTo(19f)
                horizontalLineTo(10.6f)
                close()
                moveTo(16.2f, 13f)
                horizontalLineTo(19f)
                verticalLineTo(19f)
                horizontalLineTo(16.2f)
                close()
            }
        }.build()
    }

    val Document: ImageVector by lazy {
        ImageVector.Builder("Document", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(14f, 2f)
                horizontalLineTo(6f)
                curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(8f)
                lineTo(14f, 2f)
                close()
                moveTo(16f, 18f)
                horizontalLineTo(8f)
                verticalLineTo(16f)
                horizontalLineTo(16f)
                close()
                moveTo(16f, 14f)
                horizontalLineTo(8f)
                verticalLineTo(12f)
                horizontalLineTo(16f)
                close()
                moveTo(13f, 9f)
                verticalLineTo(3.5f)
                lineTo(18.5f, 9f)
                close()
            }
        }.build()
    }

    val Person: ImageVector by lazy {
        ImageVector.Builder("Person", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(12f, 12f)
                curveTo(14.21f, 12f, 16f, 10.21f, 16f, 8f)
                curveTo(16f, 5.79f, 14.21f, 4f, 12f, 4f)
                curveTo(9.79f, 4f, 8f, 5.79f, 8f, 8f)
                curveTo(8f, 10.21f, 9.79f, 12f, 12f, 12f)
                close()
                moveTo(12f, 14f)
                curveTo(9.33f, 14f, 4f, 15.34f, 4f, 18f)
                verticalLineTo(20f)
                horizontalLineTo(20f)
                verticalLineTo(18f)
                curveTo(20f, 15.34f, 14.67f, 14f, 12f, 14f)
                close()
            }
        }.build()
    }
}
