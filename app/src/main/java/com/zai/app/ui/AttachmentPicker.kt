package com.zai.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.zai.app.data.SkillsStore
import java.io.File

/** 附件选择的统一入口（拍照 / 相册 / 文件），首页与聊天页共用 */
class AttachmentPicker(
    val launchCamera: () -> Unit,
    val launchGallery: () -> Unit,
    val launchFiles: () -> Unit,
)

/**
 * remember 一组 ActivityResult 启动器；拍到/选到的 Uri 统一回调 onPicked。
 * 拍照产物写入 cacheDir/photos，经 FileProvider 授权。
 */
@Composable
fun rememberAttachmentPicker(onPicked: (List<Uri>) -> Unit): AttachmentPicker {
    val context = LocalContext.current
    // content Uri 与真实文件的配对：FileProvider URI 的 path 是虚拟路径
    // （如 /photos/x.jpg），不能拿来做存在性判断，必须用真实 File
    var pendingCamera by remember { mutableStateOf<Pair<Uri, File>?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val pair = pendingCamera
        pendingCamera = null
        if (ok && pair != null && pair.second.exists() && pair.second.length() > 0) {
            onPicked(listOf(pair.first))
        }
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }

    return remember {
        AttachmentPicker(
            launchCamera = {
                val dir = File(context.cacheDir, "photos").apply { mkdirs() }
                val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                pendingCamera = uri to file
                takePicture.launch(uri)
            },
            launchGallery = {
                pickImages.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            launchFiles = { pickFiles.launch(arrayOf("*/*")) },
        )
    }
}

/** "+" 菜单底部弹层：拍照 / 相册 / 选择文件（+ 可选的技能入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusMenuSheet(
    onDismiss: () -> Unit,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onPickFiles: () -> Unit,
    onPickSkill: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            "添加到对话",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        SheetItem(Icons.Filled.PhotoCamera, "拍照", onPickCamera)
        SheetItem(Icons.Filled.PhotoLibrary, "相册", onPickGallery)
        SheetItem(Icons.Filled.FolderOpen, "选择文件", onPickFiles)
        if (onPickSkill != null) {
            SheetItem(Icons.Filled.Psychology, "选择技能", onPickSkill)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label, fontSize = 16.sp) },
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

/** 技能选择弹层：首页与聊天页共用，数据来自 SkillsStore */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPickerSheet(
    onDismiss: () -> Unit,
    onSelect: (com.zai.app.data.Skill) -> Unit,
) {
    val skills by SkillsStore.items.collectAsState()
    val loading by SkillsStore.loading.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            "选择技能",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        when {
            loading && skills.isEmpty() -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(22.dp)) }

            skills.isEmpty() -> Text(
                "暂无可用技能",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(skills, key = { _, s -> s.id }) { _, s ->
                    ListItem(
                        headlineContent = {
                            Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            if (s.description.isNotBlank()) {
                                Text(
                                    s.description,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(s) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
            }
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) { Text("取消") }
    }
}

/** 首页选好附件/技能后跨页面传递的暂存（单进程内存即可，不用走路由参数） */
object PendingInput {
    var uris: List<Uri> = emptyList()
    var draft: String = ""
    var skill: com.zai.app.data.Skill? = null

    fun consumeUris(): List<Uri> {
        val u = uris
        uris = emptyList()
        return u
    }

    fun consumeDraft(): String {
        val d = draft
        draft = ""
        return d
    }

    fun consumeSkill(): com.zai.app.data.Skill? {
        val s = skill
        skill = null
        return s
    }
}
