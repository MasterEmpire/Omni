package com.omni.plugin.browser.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.StudioPlaybook
import com.omni.plugin.browser.models.DEFAULT_STARTER_PLAYBOOK_HTML
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StudioLabDialog(
    bridge: HostBridge,
    playbooks: List<StudioPlaybook>,
    onSavePlaybook: (title: String, description: String, content: String, existingId: String?) -> Unit,
    onDeletePlaybook: (id: String) -> Unit,
    onLaunchPlaybook: (StudioPlaybook) -> Unit,
    onDismiss: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editingPlaybookId by remember { mutableStateOf<String?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E2228),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEditing) {
                        IconButton(
                            onClick = { isEditing = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF00E5FF))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text("🧪", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isEditing) (if (editingPlaybookId != null) "Edit Playbook" else "New Playbook") else "Studio Lab",
                        color = Color(0xFFE8EAED),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                if (!isEditing) {
                    Button(
                        onClick = {
                            editingPlaybookId = null
                            editTitle = "Custom Agent"
                            editDescription = "Puppets AI Studio and executes Nexus Python"
                            editContent = DEFAULT_STARTER_PLAYBOOK_HTML
                            isEditing = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF121418), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ New", color = Color(0xFF121418), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        text = {
            if (isEditing) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                ) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Playbook Title") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE8EAED),
                            unfocusedTextColor = Color(0xFFE8EAED),
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF3C4043)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it },
                        label = { Text("Description") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE8EAED),
                            unfocusedTextColor = Color(0xFFE8EAED),
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF3C4043)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("HTML/JS Playbook Code (Single-File Applet)", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = { editContent = DEFAULT_STARTER_PLAYBOOK_HTML },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("Reset to Boilerplate", color = Color(0xFF58A6FF), fontSize = 10.sp)
                        }
                    }

                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = Color(0xFFC9D1D9)
                        ),
                        minLines = 12,
                        maxLines = 18,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF121418),
                            unfocusedContainerColor = Color(0xFF121418),
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF3C4043)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                ) {
                    Text(
                        "Autonomous Playbooks are single-file applets that puppet Google AI Studio, run Nexus Python, and pull Conduit IDE files.",
                        color = Color(0xFF9AA0A6),
                        fontSize = 11.sp
                    )

                    if (playbooks.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🧪", fontSize = 28.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("No Playbooks saved yet", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        editingPlaybookId = null
                                        editTitle = "Autonomous Agent Starter"
                                        editDescription = "AI Studio + Nexus Python + Conduit IDE"
                                        editContent = DEFAULT_STARTER_PLAYBOOK_HTML
                                        isEditing = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                                ) {
                                    Text("Load Starter Playbook", color = Color(0xFF00E5FF), fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(
                                count = playbooks.size,
                                key = { idx -> playbooks[idx].id }
                            ) { idx ->
                                val pb = playbooks[idx]
                                val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(pb.updatedAt))

                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16181D)),
                                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                Text("⚡", fontSize = 15.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = pb.title,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(dateStr, color = Color(0xFF5F6368), fontSize = 10.sp)
                                        }

                                        if (pb.description.isNotEmpty()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = pb.description,
                                                color = Color(0xFF9AA0A6),
                                                fontSize = 11.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Spacer(Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Button(
                                                    onClick = { onLaunchPlaybook(pb) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF121418), modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Launch", color = Color(0xFF121418), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }

                                                OutlinedButton(
                                                    onClick = {
                                                        editingPlaybookId = pb.id
                                                        editTitle = pb.title
                                                        editDescription = pb.description
                                                        editContent = pb.content
                                                        isEditing = true
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF58A6FF)),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Edit", fontSize = 11.sp)
                                                }
                                            }

                                            IconButton(
                                                onClick = { onDeletePlaybook(pb.id) },
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
                        onSavePlaybook(editTitle, editDescription, editContent, editingPlaybookId)
                        isEditing = false
                    },
                    enabled = editTitle.isNotBlank() && editContent.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                ) {
                    Text("Save Playbook", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282C34))
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