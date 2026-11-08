-- The main window as a working day leaves it, for setting one platform's
-- build beside another's (Windows against the macOS reference capture): the
-- Activity sidebar with pinned, needs-you, working, asleep and archived
-- chats, a long chat open mid-turn (settled tool groups between its
-- paragraphs, a live one under them, its working row, the composer's tray)
-- and a side chat of it in the panel, itself mid-turn. All made up here; no
-- engine.
--
-- It is ui/shell_fixture.luau's shell, required as it is (its host stubs,
-- its `fixture.chats` projection), with this fixture's sessions published
-- over its own and its chats' records set in `fixture.chats`. Nothing here
-- calls back into shell_fixture's model, so its commands (the Fixture menu)
-- still act on its own sessions and would put them back: leave them be.
--
-- Run from apps/zgx (a debug build reads ui/ from where it was built, so
-- ZERON_ZGX_UI points it here):
--   ZERON_ZGX_UI="$PWD/ui" target/debug/zeron-zgx.exe fixture=compare_fixture.luau
-- No script sets a window's size: the reference is 1182 x 779 points.
-- (Its setters below write the shell's view options, which are kept: in
-- this fixture's storage, script-state/compare_fixture.luau, never the
-- app's.)

local shell = require("./shell_fixture")

local sessions = require("@account/sessions")
local store = require("@app/screens/chat/store")
local route = require("@app/screens/shell/route")
local auth = require("@account/auth")
local activity = require("@app/screens/shell/activity")
local sidebar = require("@app/screens/shell/sidebar")

local NOW = os.time()
local MIN = 60
local HOUR = 3600
local DAY = 24 * HOUR

-- You, at the sidebar's foot.
auth.account:set({ state = "signed_in", env = "production", user = { id = "u-wing", email = "wing@example.net", first_name = "Wing" } } :: any)

-- One list, the Activity view (shell_fixture groups by project for its
-- tests): what needs you, then Working, Asleep and Archived at its end.
sidebar.options.organize:set({})
activity.on:set(true)
activity.working_shelf:set(true)
-- Rows as the reference shows them: a project's tile, no harness mark.
sidebar.options.details.harness:set(false)
-- (Two things are the modules' own, kept per module and out of a
-- fixture's reach: "Star on GitHub" at the sidebar's foot, and the panel's
-- width, which splits the window until its edge is dragged. Its × and a drag
-- to the reference's 396 points are each kept from then on, in this
-- fixture's own storage, not the app's.)

-- Plugins' pages under Search, as three plugins add them.
ui.shared("plugins.pages", {} :: { any }):set({
	{ plugin = "cua", id = "driver", title = "Cua Driver", icon = "cursor" },
	{ plugin = "github", id = "prs", title = "Pull requests", icon = "pull-request" },
	{ plugin = "terrablocks", id = "board", title = "Terrablocks", icon = "widget" },
})

-- The model as its catalog names it, for the composer's foot.
store.catalog:set({
	state = "ready",
	harnesses = { { id = "claude-code", label = "Claude", mark = "claude" } },
	models = { { value = "opus", label = "Opus 5.5", harness = "claude-code" } },
} :: any)

-- The sessions: two projects on this laptop, Comet's chats (C) and
-- ZGUI's (Z).
local EPIC = "epic"
local SIDE = "side-blur"
local function chat(id: string, title: string, project: string, age: number, extra: { [string]: any }?): any
	local c: any = { id = id, title = title, project = project, device = "laptop", branch = "main", updated = NOW - age, created = NOW - age - 2 * HOUR, agent = "claude", harness = "claude-code" }
	for k, v in extra or {} do
		c[k] = v
	end
	return c
end
local chats = {
	-- Activity: done and unseen with two side chats at work; then three
	-- still moving now; then two quiet ones.
	chat("onboard", "ZGX Onboarding Flow Polish", "comet", 2, { status = "done", working_side_chats = 2 }),
	chat("zgui", "ZGUI Production Readiness", "zgui", 10, { working_side_chats = 4 }),
	chat("durable", "Durable Object Token Refresh", "comet", 30, { running_subagents = 1 }),
	chat("minecraft", "Minecraft-Inspired Game Planning", "comet", HOUR + 5 * MIN),
	chat("dictation", "Dictation Plugin Indicator", "comet", DAY + 2 * HOUR),
	-- Working: the chat open, its side chat counted in its pill.
	chat(EPIC, "Epic Zeron Product Video Timeline", "comet", 5, { status = "working", working_side_chats = 1, side_chats = 1, branch = "main" }),
}
-- Pinned: five, folded to their count (below).
local pinned = {}
for i, title in { "Release checklist", "Design tokens audit", "Engine RPC notes", "Weekly triage", "Cloud machine warmup" } do
	local id = `pin{i}`
	table.insert(chats, chat(id, title, if i % 2 == 0 then "zgui" else "comet", (3 + i) * DAY))
	table.insert(pinned, id)
end
-- Asleep: five, waking over the coming days.
for i, title in { "Revisit scroll anchoring", "Follow up on the crash report", "Bisect the font fallback", "Check the nightly", "Answer the design review" } do
	table.insert(chats, chat(`sleep{i}`, title, "comet", (1 + i) * DAY, { sleep_until = NOW + i * DAY }))
end
local archived = {}
for i = 1, 29 do
	table.insert(archived, chat(`arch{i}`, `Archived work {i}`, if i % 3 == 0 then "zgui" else "comet", (10 + i) * DAY))
end

sessions.now:set({
	state = "ready",
	here = "laptop",
	devices = { { id = "laptop", name = "work-laptop", platform = "macos", here = true, online = true } },
	projects = {
		{ id = "comet", name = "comet-native", device = "laptop", path = "/Users/wing/Documents/GitHub/comet-native", git = true, icon = { hue = "rose" } },
		{ id = "zgui", name = "zgui", device = "laptop", path = "/Users/wing/Documents/GitHub/zgui", git = true, icon = { hue = "violet" } },
	},
	chats = chats,
	archived = archived,
	children = {
		[EPIC] = { { id = SIDE, parent = EPIC, title = "Fix blurry scaled layers in zgx", device = "laptop", status = "working", updated = NOW - 5, created = NOW - 20 * MIN, agent = "claude", harness = "claude-code" } },
	},
	places = { pinned = pinned, sections = {} },
	places_ready = true,
} :: any)
-- (Pinned folded to its header and count, and the Working shelf open, as
-- the reference has them: ds/sidebar's keys, a group's fold and a shelf's
-- opening.)
local folded = table.clone(sidebar.options.folded:peek())
folded["group:::pinned"] = true
folded["::shelf:working"] = true
sidebar.options.folded:set(folded)

-- A chat's record as the host gives it: at work, on Opus, in comet-native
-- on main.
local function record(id: string, title: string, extra: { [string]: any }?): any
	local r: any = {
		id = id,
		state = "ready",
		title = title,
		harness = "claude-code",
		model = "opus",
		model_label = "Opus 5.5",
		effort = "high",
		access = "full",
		plan = false,
		project = "comet",
		device = "laptop",
		branch = "main",
		cwd = "/Users/wing/Documents/GitHub/comet-native",
		running = true,
		context = 0.42,
	}
	for k, v in extra or {} do
		r[k] = v
	end
	return r
end

local function text(id: string, words: string): any
	return { id = id, kind = "text", text = words }
end
local function exec(id: string, command: string, extra: { [string]: any }?): any
	local t: any = { id = id, kind = "exec", verb = "Ran", target = command, call = command }
	for k, v in extra or {} do
		t[k] = v
	end
	return t
end
local function thought(id: string, words: string): any
	return { id = id, kind = "thought", verb = "Thought process", call = words, prose = true }
end
local function read(id: string, path: string): any
	return { id = id, kind = "read", verb = "Read", target = path }
end
-- A settled group: its line as the host words it, its tools behind it.
local function group(id: string, summary: string, tools: { any }): any
	return { id = `g:{id}`, kind = "tools", summary = summary, tools = tools }
end
local function runs(prefix: string, n: number, command: string): { any }
	local out = {}
	for i = 1, n do
		table.insert(out, exec(`{prefix}{i}`, `{command} {i}`))
	end
	return out
end

-- The chat open: a long turn of a video edit, settled groups between its
-- paragraphs, one still going at its tail.
local FILM = "cd ~/Documents/GitHub/comet-film/apps/zgx/ui && S=/private/tmp/comet-film-preview && mkdir -p $S && cargo run -q --bin film -- --half --out $S/preview.mp4"
local WIRE = "I'm going to make the world shader's wireframe-to-Minecraft transition look much better, starting with the wire phase. The plan is glowing lines with a soft core and halo, a height gradient from violet to cyan, brighter lines near the wire front, and a black floor that fades the grid out with distance."
local epic_messages = {
	{ id = "e-u1", role = "user", text = "Cut the product film to the song: the opening on the app's real windows, the words landing on the beats, and the brand card on the final climax.", at = "Nov 8, 9:02 AM" },
	{
		id = "e-a1",
		role = "assistant",
		text = "",
		model = "Opus 5.5",
		streaming = true,
		started = NOW - (6 * MIN + 41),
		blocks = {
			text("p0", "Laying the timeline out against the song's markers first, so every cut has a beat to land on."),
			group("t0", "Thought process · ran 1 command", { thought("t0a", "Reading the markers from the song's project file."), exec("t0b", "cd ~/Documents/GitHub/comet-film && cat song/markers.json") }),
			text("p1", "Just design-lint warnings, since the app runs in strict mode. Checking the frame."),
			group("t1", "Thought 4 times · ran 7 commands · read 2 files", (function(): { any }
				local t = runs("t1x", 7, "cd ~/Documents/GitHub/comet-film && cargo run -q --bin frame --")
				table.insert(t, read("t1r1", "film/camera.rs"))
				table.insert(t, read("t1r2", "film/timeline.rs"))
				for i = 1, 4 do
					table.insert(t, thought(`t1t{i}`, "Measuring where each window sits in the frame."))
				end
				return t
			end)()),
			text("p2", "Applying the measured positions to the camera."),
			group("t2", "Thought 2 times · ran 1 command · edited 1 file", {
				thought("t2t1", "The camera's keys want the measured centres."),
				exec("t2x", "cd ~/Documents/GitHub/comet-film && cargo check -q"),
				{ id = "t2e", kind = "edit", verb = "Edited", target = "film/camera.rs", added = 14, removed = 6 },
				thought("t2t2", "That holds."),
			}),
			text("p3", 'Adding the words to the film, the "anything" montage of the real app (light theme, other hours, the cat, the world), and the brand film\'s end card on the song\'s final climax.'),
			group("t3", "Thought process · ran 1 command", { thought("t3a", "Checking the montage's timing."), exec("t3b", "cd ~/Documents/GitHub/comet-film && cargo run -q --bin film -- --check") }),
			text("p4", "Rendering a half-resolution preview of the whole film in the background to judge flow and pacing before polishing. Zooms will stay soft until the crisp-layer fix lands."),
			-- The group at work: a command run, the thought after it
			-- still being written.
			{ id = "g:t4", kind = "tools", summary = "Thought process · ran 1 command", live = true, tools = {
				exec("t4x", FILM, { output = "Compiling film v0.1.0\nFinished in 41.2s\nRendering 1920 frames at 960x540\n" }),
				thought("t4t", WIRE),
			} },
		},
	},
}

-- The side chat: a long run of commands in the crisp-layer worktree, each
-- with its reasoning after it, the last still running.
local CRISP = "cd ~/Documents/GitHub/zgx-crisp"
local SIDE_THOUGHTS = {
	"I'm checking whether the 30-unit pan on the stage's x translate stays within the 5-point margin when scaled by 20, which it does at 1.5 points, so it's a cache hit. The 300-unit move is another matter: at that scale it's 45 points, nine times the margin, so the layer is drawn again at its new place rather than moved. That's the right call for a pan that large, but the zoom's first frames pay for a full redraw.",
	"I'm checking why a 12000x12000 layer that isn't magnified still comes out at 527x427 instead of a coarser size. My crop condition should skip it since mag is 1, but for the top-level layer the scale is folded into the transform before the crop runs, so mag reads as the window's scale and the crop happens anyway. Splitting the two — the layer's own magnification and the window's — fixes it: the crop keys off the first, the raster size off both. The test for that case should cover a layer scaled down as well, since the same fold makes a shrunk layer look magnified. I'll write both cases, run them, and then run the full suite to make sure nothing else leaned on the old behaviour. If something did, it was asserting the blur, and its expectation moves with the fix rather than the fix bending to it.",
	"I'm checking whether zoom.rs failed in the earlier full run. That run may have stopped at the first failing test binary, since cargo test doesn't continue past a failure unless --no-fail-fast is set, so the zoom tests may never have run at all.",
	"I'm checking a test that had been asserting the old, blurry scale behaviour, and it now matches the zoom output exactly. That's strong evidence the fix works. I'll update the test's expectation to the crisp size and note why in its comment, so the next person who reads it doesn't think the number is arbitrary. Then the whole suite once more with --no-fail-fast, and the before and after captures for the pull request.",
}
local side_tools = {
	thought("s0", "Starting from the layer cache's keys."),
	exec("s1", `{CRISP}/crates/ui && cargo test -q layer_cache -- --nocapture`),
	thought("s1t", SIDE_THOUGHTS[1]),
	exec("s2", `{CRISP} && CARGO_TARGET_DIR=/tmp/crisp cargo build -q -p zgx`),
	exec("s3", `{CRISP} && grep -rn "fn crop_for" crates/ui/src`),
	thought("s3t", SIDE_THOUGHTS[2]),
	exec("s4", `{CRISP} && python3 tools/capture.py --scale 2 --out /tmp/crisp/after.png`),
	thought("s4t", SIDE_THOUGHTS[3]),
	exec("s5", `{CRISP} && CARGO_TARGET_DIR=/tmp/crisp cargo test -q --no-fail-fast zoom`),
	thought("s5t", SIDE_THOUGHTS[4]),
	exec("s6", `{CRISP} && python3 tools/capture.py --scale 2 --out /tmp/crisp/before.png`, { output = "capturing…\n" }),
}
local side_messages = {
	{ id = "s-u1", role = "user", text = "Zoomed layers come out blurry when the window is scaled. Find why and fix it.", at = "Nov 8, 8:49 AM" },
	{
		id = "s-a1",
		role = "assistant",
		text = "",
		model = "Opus 5.5",
		streaming = true,
		started = NOW - (15 * MIN + 13),
		blocks = {
			text("sp0", "Looking at how a scaled layer picks its raster size."),
			{ id = "g:s0", kind = "tools", summary = "Thought 5 times · ran 6 commands", live = true, tools = side_tools },
		},
	},
}

-- The composer's tray: a subagent and a command running in the background.
local TASKS = {
	{ id = "ag1", kind = "agent", name = "Time the cuts to the beats", since = NOW - 4 * MIN },
	{ id = "bg1", kind = "process", name = "cargo run --bin film", detail = "preview", since = NOW - 5 * MIN },
}

ui.shared("fixture.chats", {} :: { [string]: any }):set({
	[EPIC] = { session = record(EPIC, "Epic Zeron Product Video Timeline", { tasks = TASKS }), messages = epic_messages },
	[SIDE] = { session = record(SIDE, "Fix blurry scaled layers in zgx", { cwd = "/Users/wing/Documents/GitHub/zgx-crisp" }), messages = side_messages },
})

-- The chat opened, then its side chat in its panel once the shell is up
-- (the panel sets the lanes' opener as it loads).
route.open_session(EPIC)
ui.after(300, function()
	local lanes = require("@app/screens/chat/lanes")
	lanes.open(EPIC, { kind = "side_chat", id = SIDE, title = "Fix blurry scaled layers in zgx" })
end)

return shell
