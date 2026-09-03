package com.omni.plugin.browser.ui.dialogs

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.SmartNote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SmartNotesDialog(
    bridge: HostBridge,
    notes: List<SmartNote>,
    onSaveNote: (title: String, content: String, existingId: String?) -> Unit,
    onDeleteNote: (id: String) -> Unit,
    onInjectToPage: (text: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var editingNoteId by remember { mutableStateOf<String?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredNotes = remember(notes, searchQuery) {
        if (searchQuery.isBlank()) notes
        else notes.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.content.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📝", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isEditing) (if (editingNoteId != null) "Edit Note" else "New Note") else "Smart Notes",
                        color = Color(0xFFE8EAED),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                if (!isEditing) {
                    Button(
                        onClick = {
                            editingNoteId = null
                            editTitle = ""
                            editContent = ""
                            isEditing = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        text = {
            if (isEditing) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                ) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title (Optional)") },
                        placeholder = { Text("Auto-derived from content if blank", color = Color(0xFF5F6368), fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE8EAED),
                            unfocusedTextColor = Color(0xFFE8EAED),
                            focusedBorderColor = Color(0xFF8AB4F8),
                            unfocusedBorderColor = Color(0xFF3C4043)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("Content") },
                        placeholder = { Text("Type or paste thoughts, prompt drafts, code snippets...", color = Color(0xFF5F6368), fontSize = 11.sp) },
                        minLines = 7,
                        maxLines = 14,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE8EAED),
                            unfocusedTextColor = Color(0xFFE8EAED),
                            focusedBorderColor = Color(0xFF8AB4F8),
                            unfocusedBorderColor = Color(0xFF3C4043)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (clipText.isNotEmpty()) {
                                        editContent = if (editContent.isEmpty()) clipText else "$editContent\n$clipText"
                                        bridge.showToast("Pasted from clipboard")
                                    }
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("📋 Paste Clipboard", fontSize = 10.sp, color = Color(0xFF8AB4F8))
                        }
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                ) {
                    if (notes.isNotEmpty()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search notes...", color = Color(0xFF5F6368), fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF1F2227),
                                unfocusedContainerColor = Color(0xFF1F2227),
                                focusedBorderColor = Color(0xFF8AB4F8),
                                unfocusedBorderColor = Color(0xFF3C4043),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        )
                    }

                    if (filteredNotes.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📄", fontSize = 28.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (searchQuery.isNotBlank()) "No notes match your search" else "No saved notes yet",
                                    color = Color(0xFF9AA0A6),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(
                                count = filteredNotes.size,
                                key = { idx -> filteredNotes[idx].id }
                            ) { idx ->
                                val note = filteredNotes[idx]
                                val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(note.updatedAt))

                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2227)),
                                    border = BorderStroke(1.dp, Color(0xFF3C4043)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = note.title,
                                                color = Color(0xFFE8EAED),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = dateStr,
                                                color = Color(0xFF5F6368),
                                                fontSize = 10.sp
                                            )
                                        }

                                        Spacer(Modifier.height(4.dp))

                                        Text(
                                            text = note.content,
                                            color = Color(0xFF9AA0A6),
                                            fontSize = 11.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 15.sp
                                        )

                                        Spacer(Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = { onInjectToPage(note.content) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("⚡ Inject", fontSize = 11.sp, color = Color(0xFF81C995), fontWeight = FontWeight.Bold)
                                            }

                                            TextButton(
                                                onClick = {
                                                    bridge.copyToClipboard(note.content)
                                                    bridge.showToast("Copied to clipboard")
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("📋 Copy", fontSize = 11.sp, color = Color(0xFF8AB4F8))
                                            }

                                            IconButton(
                                                onClick = {
                                                    editingNoteId = note.id
                                                    editTitle = note.title
                                                    editContent = note.content
                                                    isEditing = true
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Text("✏️", fontSize = 12.sp)
                                            }

                                            IconButton(
                                                onClick = { onDeleteNote(note.id) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF28B82), modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isEditing) {
                Button(
                    onClick = {
                        onSaveNote(editTitle, editContent, editingNoteId)
                        isEditing = false
                    },
                    enabled = editContent.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4043))
                ) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (isEditing) {
                TextButton(onClick = { isEditing = false }) {
                    Text("Cancel", color = Color(0xFF9AA0A6))
                }
            }
        }
    )
}