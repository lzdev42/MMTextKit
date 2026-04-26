package xyz.mmtextkit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import xyz.columnscript.columnscript.components.VTextField
import mmtextkit.composeapp.generated.resources.Res
import mmtextkit.composeapp.generated.resources.NotoSansMongolian_Regular

@Composable
fun App() {
    MaterialTheme {
        val mongolianFont = FontFamily(Font(Res.font.NotoSansMongolian_Regular))
        
        var textValue by remember { 
            mutableStateOf(TextFieldValue(
                "汉 你好世界\n" +
                "蒙 ᠮᠣᠩᠭᠣᠯ ᠪᠢᠴᠢᠭ\n" +
                "满 ᡨᡠᠮᡝᠨ ᠪᠠᡳᡨᠠ ᡤᡡᠨᡳᠨ ᡩᡝ ᠠᠴᠠᡴᡳᠨᡳ\n" +
                "日 こんにちは\n" +
                "韩 안녕하세요\n" +
                "英 Hello MMTextKit 2026\n" +
                "表情 😂🌈🚀🔥❤️"
            )) 
        }

        Scaffold { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.White)
                    .padding(24.dp)
            ) {
                VTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    verticalFontFamily = mongolianFont,
                    style = TextStyle(
                        fontSize = 24.sp,
                        color = Color.Black
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}