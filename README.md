# MMTextKit

⚠️ **注意：还没做完呢。**
目前只在 **JVM (Desktop)** 上跑通了，iOS 和 Android 的对应代码还没写。等全平台都跑通了、没 Bug 了，我才会把它打包成正式的库。

---

MMTextKit 是给 Compose Multiplatform 写的垂直排版工具，能让蒙古文、满文跟汉字混在一起竖着写。

## 怎么用？

核心组件就是 `VTextField`。你可以把它看作是垂直排版版的 **`TextField`**。

```kotlin
val textValue by remember { mutableStateOf(TextFieldValue("汉字\nᠮᠣᠩᠭᠣᠯ")) }

VTextField(
    value = textValue,
    onValueChange = { /* 更新状态 */ },
    verticalFontFamily = FontFamily(Font(Res.font.NotoSansMongolian_Regular)), // 不设置蒙/满字体，它就用系统默认字体，建议找个正确的字体使用，这个字体不会应用到蒙/满文以外的文本上
    modifier = Modifier.fillMaxSize()
)
```

## 许可证
MIT