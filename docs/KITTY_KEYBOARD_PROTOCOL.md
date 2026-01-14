# Kitty Keyboard Protocol Implementation

## Overview

VibeTTY implements the [Kitty Keyboard Protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/) (also known as "progressive keyboard enhancement" or "CSI u encoding"). This protocol allows terminal applications to receive full modifier information for keys that traditionally don't report modifiers, such as Enter, Tab, Backspace, and Escape.

**Primary Use Case**: Applications like Claude Code can distinguish between Enter (submit) and Shift+Enter (newline) for multi-line input.

## Protocol Specification

### Key Encoding Format

```
CSI unicode-key-code ; modifiers u
```

Where:
- `CSI` = `ESC [` (0x1B 0x5B)
- `unicode-key-code` = Unicode codepoint of the key
- `modifiers` = modifier bits + 1
- `u` = final character indicating CSI u format

### Modifier Encoding

| Modifier | Bit Value | Transmitted Value |
|----------|-----------|-------------------|
| Shift    | 1         | 2 (1+1)           |
| Alt      | 2         | 3 (2+1)           |
| Ctrl     | 4         | 5 (4+1)           |
| Shift+Ctrl | 5       | 6 (5+1)           |
| Shift+Alt | 3        | 4 (3+1)           |

### Examples

| Key Combination | Escape Sequence | Explanation |
|-----------------|-----------------|-------------|
| Shift+Enter     | `ESC[13;2u`     | 13=Enter codepoint, 2=Shift+1 |
| Ctrl+Enter      | `ESC[13;5u`     | 13=Enter, 5=Ctrl+1 |
| Shift+Tab       | `ESC[9;2u`      | 9=Tab codepoint |
| Alt+Backspace   | `ESC[127;3u`    | 127=Backspace, 3=Alt+1 |

### Protocol Control Sequences

Applications use these sequences to negotiate protocol support:

| Sequence | Direction | Purpose |
|----------|-----------|---------|
| `CSI > flags u` | App → Terminal | Push/enable mode with flags |
| `CSI < u` | App → Terminal | Pop/disable mode |
| `CSI < count u` | App → Terminal | Pop multiple modes |
| `CSI ? u` | App → Terminal | Query current mode |
| `CSI ? flags u` | Terminal → App | Response to query |

### Progressive Enhancement Flags

| Flag | Value | Description |
|------|-------|-------------|
| FLAG_DISAMBIGUATE | 1 | Disambiguate escape codes |
| FLAG_REPORT_EVENT_TYPES | 2 | Report key press/repeat/release |
| FLAG_REPORT_ALTERNATE_KEYS | 4 | Report shifted/base layout keys |
| FLAG_REPORT_ALL_KEYS | 8 | Report all keys as escape codes |
| FLAG_REPORT_TEXT | 16 | Report associated text |

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         Android App                              │
│  ┌─────────────────┐    ┌──────────────────┐                    │
│  │ TerminalBridge  │───>│ TerminalManager  │                    │
│  │                 │    │ (reads prefs)    │                    │
│  └────────┬────────┘    └──────────────────┘                    │
│           │                                                      │
│           ▼                                                      │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                      termlib                                 ││
│  │  ┌──────────────────┐    ┌─────────────────────────────┐   ││
│  │  │ KeyboardHandler  │───>│ TerminalEmulator            │   ││
│  │  │                  │    │  - KittyKeyboardProtocol    │   ││
│  │  │ shouldEncode?    │    │  - Mode stack               │   ││
│  │  │ encodeKey()      │    │  - CSI sequence handling    │   ││
│  │  └──────────────────┘    └──────────────┬──────────────┘   ││
│  │                                          │                   ││
│  │                                          ▼                   ││
│  │                          ┌───────────────────────────────┐  ││
│  │                          │ Terminal.cpp (Native)         │  ││
│  │                          │  - CSI fallback handler       │  ││
│  │                          │  - Forwards to Kotlin         │  ││
│  │                          └───────────────────────────────┘  ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### Key Files

#### termlib

| File | Purpose |
|------|---------|
| `KittyKeyboardProtocol.kt` | Protocol state management, mode stack, key encoding |
| `TerminalEmulator.kt` | Handles CSI sequences, exposes protocol to keyboard handler |
| `KeyboardHandler.kt` | Intercepts keys, decides when to use Kitty encoding |
| `TerminalCallbacks.kt` | Interface for CSI callback from native layer |
| `Terminal.cpp` | Native CSI fallback handler, forwards to Kotlin |
| `Terminal.h` | Native layer declarations |

#### app

| File | Purpose |
|------|---------|
| `PreferenceConstants.kt` | `KITTY_KEYBOARD_PROTOCOL` setting constant |
| `SettingsViewModel.kt` | Setting state and update function |
| `SettingsScreen.kt` | UI toggle in Keyboard section |
| `TerminalManager.kt` | Preference accessor, live update handling |
| `TerminalBridge.kt` | Applies setting on terminal creation |
| `strings.xml` | "Enhanced keyboard protocol" UI strings |

## Implementation Details

### Protocol Activation

The protocol requires **two conditions** to be active:

1. **User preference enabled**: Settings → Keyboard → "Enhanced keyboard protocol"
2. **Application requests it**: App sends `CSI > flags u`

This dual-gate approach ensures:
- Users have control over the feature
- Legacy applications aren't affected
- Protocol is only active when both terminal and app support it

### Mode Stack

The protocol maintains separate mode stacks for main and alternate screens:

```kotlin
class KittyKeyboardProtocol {
    private val mainScreenStack = ArrayDeque<Int>()
    private val altScreenStack = ArrayDeque<Int>()
    var isAlternateScreen: Boolean = false

    val isActive: Boolean
        get() = isEnabledByUser && currentStack.isNotEmpty()
}
```

### Key Interception Flow

```kotlin
// In KeyboardHandler.onKeyEvent()
val vtermKey = mapToVTermKey(key)
if (vtermKey != null) {
    // Check if Kitty should handle this key
    if (terminalEmulator.shouldEncodeKitty(vtermKey, modifiers)) {
        val encoded = terminalEmulator.encodeKittyKey(vtermKey, modifiers)
        if (encoded != null) {
            terminalEmulator.writeKeyboardOutput(encoded)
            return true
        }
    }
    // Fall back to normal libvterm handling
    terminalEmulator.dispatchKey(modifiers, vtermKey)
}
```

### CSI Sequence Handling (Native → Kotlin)

1. libvterm receives CSI sequence it doesn't recognize
2. `termCsiFallback()` in Terminal.cpp is called
3. Sequence details forwarded to Kotlin via JNI
4. `TerminalEmulator.onCsiSequence()` processes the sequence
5. For `CSI ? u` queries, response written back to PTY

```cpp
// Terminal.cpp
int Terminal::termCsiFallback(const char* leader, const long args[],
                               int argcount, const char* intermed,
                               char command, void* user) {
    auto* term = static_cast<Terminal*>(user);
    return term->invokeCsiSequence(leader, args, argcount, intermed, command);
}
```

### Keys Supported

Keys that receive Kitty encoding when modifiers are present:

| Key | Unicode Codepoint |
|-----|-------------------|
| Enter | 13 |
| Tab | 9 |
| Backspace | 127 |
| Escape | 27 |

## Testing

### Manual Testing with cat -v

1. Enable setting: Settings → Keyboard → "Enhanced keyboard protocol"
2. Connect to any host
3. Run `cat -v`
4. Press Shift+Enter
5. Expected output: `^[[13;2u`

### Protocol Negotiation Test

```bash
# Query current mode (should respond with CSI ? 0 u if no app has pushed)
printf '\e[?u'

# Push mode with disambiguate flag
printf '\e[>1u'

# Query again (should respond with CSI ? 1 u)
printf '\e[?u'

# Pop mode
printf '\e[<u'
```

### Testing with Claude Code

1. Enable "Enhanced keyboard protocol" in VibeTTY settings
2. SSH to a host running Claude Code
3. In Claude Code's input, press Shift+Enter
4. Should create a newline without submitting

## Compatibility

### Applications That Support Kitty Protocol

- Kitty terminal (reference implementation)
- WezTerm
- Alacritty (partial)
- foot
- Claude Code
- Many modern CLI tools

### TERM Variable

Some applications check the TERM environment variable to detect Kitty support. Setting `TERM=xterm-kitty` may improve compatibility, but VibeTTY also responds to protocol queries regardless of TERM.

## References

- [Kitty Keyboard Protocol Specification](https://sw.kovidgoyal.net/kitty/keyboard-protocol/)
- [libvterm Documentation](https://www.leonerd.org.uk/code/libvterm/)
- [VT100.net - Terminal Control Sequences](https://vt100.net/)
