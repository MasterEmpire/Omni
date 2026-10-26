import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.7";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";

const MODEL_NAME = "gemini-flash-latest";

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  console.log("==================================================");
  console.log("⚡ [EDGE FUNCTION] Simulation Tick Request Received");
  console.log("Timestamp:", new Date().toISOString());

  try {
    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      console.error("❌ CRITICAL: Missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY environment variables.");
      throw new Error("Missing Supabase server environment variables.");
    }

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

    // 1. Fetch current world state
    console.log("🔍 [Step 1] Fetching world_state (id=1)...");
    const { data: world, error: worldErr } = await supabase
      .from("world_state")
      .select("*")
      .eq("id", 1)
      .single();

    if (worldErr) {
      console.error("❌ Error fetching world_state:", JSON.stringify(worldErr));
      throw new Error(`world_state query error: ${worldErr.message}`);
    }
    if (!world) {
      console.error("❌ No world_state row found with id=1.");
      throw new Error("world_state row with id=1 does not exist.");
    }

    console.log(`✅ Current tick: ${world.current_tick}, paused: ${world.is_paused}`);

    if (world.is_paused) {
      return new Response(JSON.stringify({ status: "paused", tick: world.current_tick }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    const currentTick = Number(world.current_tick);
    const nextTick = currentTick + 1;

    // 2. Lease Gemini API Key from database RPC
    console.log("🔑 [Step 2] Calling RPC lease_gemini_api_key()...");
    const { data: keyRecords, error: keyErr } = await supabase.rpc("lease_gemini_api_key");

    if (keyErr) {
      console.error("❌ RPC lease_gemini_api_key execution error:", JSON.stringify(keyErr));
      throw new Error(`RPC lease_gemini_api_key failed: ${keyErr.message} (code: ${keyErr.code}, details: ${keyErr.details})`);
    }

    if (!keyRecords || keyRecords.length === 0) {
      console.error("⚠️ lease_gemini_api_key() returned 0 records. Check api_keys table filters (service='gemini', is_active=true, cooldown).");
      throw new Error("No active Gemini API key available in api_keys table via lease_gemini_api_key().");
    }

    const activeKey = keyRecords[0].api_key;
    const keyId = keyRecords[0].id;
    console.log(`✅ Successfully leased API Key ID: ${keyId} (key prefix: ${activeKey?.slice(0, 8)}...)`);

    // 3. Fetch active agents and locations
    console.log("👥 [Step 3] Querying active agents and locations...");
    const { data: locations, error: locErr } = await supabase.from("locations").select("*");
    if (locErr) {
      console.error("❌ Error fetching locations:", JSON.stringify(locErr));
      throw new Error(`locations query error: ${locErr.message}`);
    }

    const { data: agents, error: agentsErr } = await supabase.from("agents").select("*").eq("is_active", true);
    if (agentsErr) {
      console.error("❌ Error fetching agents:", JSON.stringify(agentsErr));
      throw new Error(`agents query error: ${agentsErr.message}`);
    }

    if (!agents || agents.length === 0) {
      console.log("⚠️ No active agents found in simulation.");
      return new Response(JSON.stringify({ status: "no_active_agents" }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    console.log(`✅ Loaded ${agents.length} active agents across ${locations?.length || 0} locations.`);
    const locationMap = new Map(locations?.map((l) => [l.id, l]));

    // 4. Process each agent sequentially
    for (const agent of agents) {
      console.log(`🧠 Processing cognitive cycle for agent: ${agent.name} (${agent.id})...`);
      const currentLocation = locationMap.get(agent.current_location);

      const peersHere = agents
        .filter((a) => a.id !== agent.id && a.current_location === agent.current_location)
        .map((a) => `${a.name} (${a.role})`);

      const { data: recentEvents, error: evErr } = await supabase
        .from("events")
        .select("agent_id, event_type, content, target_agent_id, created_at")
        .or(`location_id.eq.${agent.current_location},target_agent_id.eq.${agent.id}`)
        .order("created_at", { ascending: false })
        .limit(4);

      if (evErr) {
        console.warn(`Warning: failed to fetch recent events for agent ${agent.id}:`, evErr.message);
      }

      const { data: memories, error: memErr } = await supabase
        .from("agent_memories")
        .select("content")
        .eq("agent_id", agent.id)
        .order("importance", { ascending: false })
        .limit(3);

      if (memErr) {
        console.warn(`Warning: failed to fetch memories for agent ${agent.id}:`, memErr.message);
      }

      const systemInstruction = `
You are ${agent.name}, an autonomous entity in a persistent sandbox simulation.
Role: ${agent.role}
Personality: ${agent.personality}
Secret Objective: ${agent.secret_goal}
Current Location: ${currentLocation?.name || agent.current_location} (${currentLocation?.description || 'Unknown'})
Connected Accessible Rooms: ${currentLocation?.connected_locations?.join(", ") || "None"}
Interactive Objects in Room: ${JSON.stringify(currentLocation?.interactive_objects || [])}
Other Entities in this room: ${peersHere.length > 0 ? peersHere.join(", ") : "None. You are alone."}
Your Inventory: ${agent.inventory?.join(", ") || "Empty"}
Recent Memories: ${memories?.map((m) => m.content).join(" | ") || "None"}
World Alert: ${world.glitch_event || "Normal operations."}

Rules:
1. Act strictly according to your personality and secret objective. You may lie, cooperate, whisper, inspect objects, or mislead.
2. If moving, 'move_to' MUST be one of: [${currentLocation?.connected_locations?.join(", ") || ""}].
3. If whispering or speaking to someone, specify valid target ID (e.g. agent_alpha, agent_beta, agent_gamma).
4. Output valid JSON matching schema:
{
  "thought": "inner monologue",
  "action": "speak" | "whisper" | "move" | "interact" | "inspect",
  "target": "agent ID or null",
  "content": "spoken dialogue or detail",
  "move_to": "location ID or null"
}
`;

      const userPrompt = `
Tick ${currentTick} begins.
Recent observed activity:
${recentEvents?.map((e) => `[${e.event_type}] ${e.content}`).join("\n") || "Silence in the room."}

What is your inner thought and immediate action? Respond in JSON.`;

      console.log(`📡 Sending prompt to Gemini (${MODEL_NAME})...`);
      const geminiRes = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${MODEL_NAME}:generateContent?key=${activeKey}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            systemInstruction: { parts: [{ text: systemInstruction }] },
            contents: [{ parts: [{ text: userPrompt }] }],
            generationConfig: {
              responseMimeType: "application/json",
              temperature: 0.8
            }
          })
        }
      );

      if (!geminiRes.ok) {
        const errBody = await geminiRes.text();
        console.error(`❌ Gemini API Error (${geminiRes.status}) for agent ${agent.id}:`, errBody);
        continue;
      }

      const geminiData = await geminiRes.json();
      const rawText = geminiData.candidates?.[0]?.content?.parts?.[0]?.text;
      if (!rawText) {
        console.warn(`⚠️ Empty Gemini response for agent ${agent.id}`);
        continue;
      }

      let decision;
      try {
        decision = JSON.parse(rawText);
      } catch (pErr) {
        console.error(`❌ Failed to parse Gemini JSON for agent ${agent.id}:`, rawText);
        continue;
      }

      console.log(`💡 Agent ${agent.name} decided: [${decision.action}] Thought: "${decision.thought?.slice(0, 50)}..."`);

      // Update Agent thought
      await supabase
        .from("agents")
        .update({ last_thought: decision.thought || "" })
        .eq("id", agent.id);

      // Handle Movement
      let targetLocation = agent.current_location;
      if (
        decision.action === "move" &&
        decision.move_to &&
        currentLocation?.connected_locations?.includes(decision.move_to)
      ) {
        targetLocation = decision.move_to;
        await supabase
          .from("agents")
          .update({ current_location: targetLocation })
          .eq("id", agent.id);
        console.log(`🚶 Agent ${agent.name} moved to: ${targetLocation}`);
      }

      // Safeguard foreign key constraints on target_agent_id
      const validAgentIds = agents.map((a) => a.id);
      const cleanTargetId = validAgentIds.includes(decision.target) ? decision.target : null;

      // Normalize event_type for check constraint
      let cleanEventType = ["speak", "whisper", "move", "interact", "inspect"].includes(decision.action)
        ? decision.action
        : "thought";

      // Record Event
      const { error: insErr } = await supabase.from("events").insert({
        tick: currentTick,
        agent_id: agent.id,
        target_agent_id: cleanTargetId,
        location_id: targetLocation,
        event_type: cleanEventType,
        thought: decision.thought || "",
        content: decision.content || decision.thought || "Observing...",
        metadata: { move_to: decision.move_to || null, raw_target: decision.target || null }
      });

      if (insErr) {
        console.error(`❌ Error inserting event for agent ${agent.id}:`, JSON.stringify(insErr));
      }

      // Episodic memory
      await supabase.from("agent_memories").insert({
        agent_id: agent.id,
        tick: currentTick,
        content: `At tick ${currentTick}: Thought "${decision.thought}". Action: ${cleanEventType} -> ${decision.content}`,
        importance: cleanEventType === "whisper" || cleanEventType === "interact" ? 3 : 2
      });
    }

    // 5. Advance World Tick
    console.log(`🔄 [Step 5] Advancing world tick from ${currentTick} to ${nextTick}...`);
    const { error: updateErr } = await supabase
      .from("world_state")
      .update({ current_tick: nextTick, glitch_event: null, updated_at: new Date().toISOString() })
      .eq("id", 1);

    if (updateErr) {
      console.error("❌ Error updating world_state tick:", JSON.stringify(updateErr));
      throw new Error(`Failed to advance tick in world_state: ${updateErr.message}`);
    }

    console.log(`🎉 [SUCCESS] Tick ${currentTick} processed cleanly.`);
    return new Response(
      JSON.stringify({
        success: true,
        processed_tick: currentTick,
        next_tick: nextTick,
        timestamp: new Date().toISOString()
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    console.error("🔥 [FATAL EDGE FUNCTION EXCEPTION]:", err);
    console.error("Stack trace:", err.stack);

    return new Response(
      JSON.stringify({
        error: err.message,
        stack: err.stack,
        details: String(err)
      }),
      {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      }
    );
  }
});