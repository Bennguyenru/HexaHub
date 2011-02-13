#ifndef DM_HID_H
#define DM_HID_H

#include <stdint.h>

// Let glfw decide the constants
#include "hid_glfw_defines.h"

namespace dmHID
{
    typedef struct Gamepad* HGamepad;

    const HGamepad INVALID_GAMEPAD_HANDLE = 0;

    const static uint32_t MAX_GAMEPAD_COUNT = HID_MAX_GAMEPAD_COUNT;
    const static uint32_t MAX_GAMEPAD_AXIS_COUNT = 32;
    const static uint32_t MAX_GAMEPAD_BUTTON_COUNT = 32;

    enum Key
    {
        KEY_SPACE = HID_KEY_SPACE,
        KEY_EXCLAIM = HID_KEY_EXCLAIM,
        KEY_QUOTEDBL = HID_KEY_QUOTEDBL,
        KEY_HASH = HID_KEY_HASH,
        KEY_DOLLAR = HID_KEY_DOLLAR,
        KEY_AMPERSAND = HID_KEY_AMPERSAND,
        KEY_QUOTE = HID_KEY_QUOTE,
        KEY_LPAREN = HID_KEY_LPAREN,
        KEY_RPAREN = HID_KEY_RPAREN,
        KEY_ASTERISK = HID_KEY_ASTERISK,
        KEY_PLUS = HID_KEY_PLUS,
        KEY_COMMA = HID_KEY_COMMA,
        KEY_MINUS = HID_KEY_MINUS,
        KEY_PERIOD = HID_KEY_PERIOD,
        KEY_SLASH = HID_KEY_SLASH,

        KEY_0 = HID_KEY_0,
        KEY_1 = HID_KEY_1,
        KEY_2 = HID_KEY_2,
        KEY_3 = HID_KEY_3,
        KEY_4 = HID_KEY_4,
        KEY_5 = HID_KEY_5,
        KEY_6 = HID_KEY_6,
        KEY_7 = HID_KEY_7,
        KEY_8 = HID_KEY_8,
        KEY_9 = HID_KEY_9,

        KEY_COLON = HID_KEY_COLON,
        KEY_SEMICOLON = HID_KEY_SEMICOLON,
        KEY_LESS = HID_KEY_LESS,
        KEY_EQUALS = HID_KEY_EQUALS,
        KEY_GREATER = HID_KEY_GREATER,
        KEY_QUESTION = HID_KEY_QUESTION,
        KEY_AT = HID_KEY_AT,

        KEY_A = HID_KEY_A,
        KEY_B = HID_KEY_B,
        KEY_C = HID_KEY_C,
        KEY_D = HID_KEY_D,
        KEY_E = HID_KEY_E,
        KEY_F = HID_KEY_F,
        KEY_G = HID_KEY_G,
        KEY_H = HID_KEY_H,
        KEY_I = HID_KEY_I,
        KEY_J = HID_KEY_J,
        KEY_K = HID_KEY_K,
        KEY_L = HID_KEY_L,
        KEY_M = HID_KEY_M,
        KEY_N = HID_KEY_N,
        KEY_O = HID_KEY_O,
        KEY_P = HID_KEY_P,
        KEY_Q = HID_KEY_Q,
        KEY_R = HID_KEY_R,
        KEY_S = HID_KEY_S,
        KEY_T = HID_KEY_T,
        KEY_U = HID_KEY_U,
        KEY_V = HID_KEY_V,
        KEY_W = HID_KEY_W,
        KEY_X = HID_KEY_X,
        KEY_Y = HID_KEY_Y,
        KEY_Z = HID_KEY_Z,

        KEY_LBRACKET = HID_KEY_LBRACKET,
        KEY_BACKSLASH = HID_KEY_BACKSLASH,
        KEY_RBRACKET = HID_KEY_RBRACKET,
        KEY_CARET = HID_KEY_CARET,
        KEY_UNDERSCORE = HID_KEY_UNDERSCORE,
        KEY_BACKQUOTE = HID_KEY_BACKQUOTE,

        KEY_LBRACE = HID_KEY_LBRACE,
        KEY_PIPE = HID_KEY_PIPE,
        KEY_RBRACE = HID_KEY_RBRACE,
        KEY_TILDE = HID_KEY_TILDE,
        /* End of ASCII mapped keysyms */

        /* Special keys */
        KEY_ESC = HID_KEY_ESC,
        KEY_F1 = HID_KEY_F1,
        KEY_F2 = HID_KEY_F2,
        KEY_F3 = HID_KEY_F3,
        KEY_F4 = HID_KEY_F4,
        KEY_F5 = HID_KEY_F5,
        KEY_F6 = HID_KEY_F6,
        KEY_F7 = HID_KEY_F7,
        KEY_F8 = HID_KEY_F8,
        KEY_F9 = HID_KEY_F9,
        KEY_F10 = HID_KEY_F10,
        KEY_F11 = HID_KEY_F11,
        KEY_F12 = HID_KEY_F12,
        KEY_UP = HID_KEY_UP,
        KEY_DOWN = HID_KEY_DOWN,
        KEY_LEFT = HID_KEY_LEFT,
        KEY_RIGHT = HID_KEY_RIGHT,
        KEY_LSHIFT = HID_KEY_LSHIFT,
        KEY_RSHIFT = HID_KEY_RSHIFT,
        KEY_LCTRL = HID_KEY_LCTRL,
        KEY_RCTRL = HID_KEY_RCTRL,
        KEY_LALT = HID_KEY_LALT,
        KEY_RALT = HID_KEY_RALT,
        KEY_TAB = HID_KEY_TAB,
        KEY_ENTER = HID_KEY_ENTER,
        KEY_BACKSPACE = HID_KEY_BACKSPACE,
        KEY_INSERT = HID_KEY_INSERT,
        KEY_DEL = HID_KEY_DEL,
        KEY_PAGEUP = HID_KEY_PAGEUP,
        KEY_PAGEDOWN = HID_KEY_PAGEDOWN,
        KEY_HOME = HID_KEY_HOME,
        KEY_END = HID_KEY_END,
        KEY_KP_0 = HID_KEY_KP_0,
        KEY_KP_1 = HID_KEY_KP_1,
        KEY_KP_2 = HID_KEY_KP_2,
        KEY_KP_3 = HID_KEY_KP_3,
        KEY_KP_4 = HID_KEY_KP_4,
        KEY_KP_5 = HID_KEY_KP_5,
        KEY_KP_6 = HID_KEY_KP_6,
        KEY_KP_7 = HID_KEY_KP_7,
        KEY_KP_8 = HID_KEY_KP_8,
        KEY_KP_9 = HID_KEY_KP_9,
        KEY_KP_DIVIDE = HID_KEY_KP_DIVIDE,
        KEY_KP_MULTIPLY = HID_KEY_KP_MULTIPLY,
        KEY_KP_SUBTRACT = HID_KEY_KP_SUBTRACT,
        KEY_KP_ADD = HID_KEY_KP_ADD,
        KEY_KP_DECIMAL = HID_KEY_KP_DECIMAL,
        KEY_KP_EQUAL = HID_KEY_KP_EQUAL,
        KEY_KP_ENTER = HID_KEY_KP_ENTER,

        MAX_KEY_COUNT = HID_KEY_KP_ENTER + 1
    };

    enum MouseButton
    {
        MOUSE_BUTTON_LEFT = HID_MOUSE_BUTTON_LEFT,
        MOUSE_BUTTON_MIDDLE = HID_MOUSE_BUTTON_MIDDLE,
        MOUSE_BUTTON_RIGHT = HID_MOUSE_BUTTON_RIGHT,
        MOUSE_BUTTON_1 = HID_MOUSE_BUTTON_1,
        MOUSE_BUTTON_2 = HID_MOUSE_BUTTON_2,
        MOUSE_BUTTON_3 = HID_MOUSE_BUTTON_3,
        MOUSE_BUTTON_4 = HID_MOUSE_BUTTON_4,
        MOUSE_BUTTON_5 = HID_MOUSE_BUTTON_5,
        MOUSE_BUTTON_6 = HID_MOUSE_BUTTON_6,
        MOUSE_BUTTON_7 = HID_MOUSE_BUTTON_7,
        MOUSE_BUTTON_8 = HID_MOUSE_BUTTON_8,

        MAX_MOUSE_BUTTON_COUNT = HID_MOUSE_BUTTON_8 + 1
    };

    struct KeyboardPacket
    {
        uint32_t m_Keys[MAX_KEY_COUNT / 32 + 1];
    };

    struct MousePacket
    {
        int32_t m_PositionX, m_PositionY;
        int32_t m_Wheel;
        uint32_t m_Buttons[MAX_MOUSE_BUTTON_COUNT / 32 + 1];
    };

    struct GamepadPacket
    {
        float m_Axis[MAX_GAMEPAD_AXIS_COUNT];
        uint32_t m_Buttons[MAX_GAMEPAD_BUTTON_COUNT / 32 + 1];
    };

    /**
     * Start hid system.
     */
    void Initialize();

    /**
     * Shut down hid system.
     */
    void Finalize();

    /**
     * Poll input.
     */
    void Update();

    /**
     * Create/open a new gamepad
     * @param index Gamepad index
     * @return Handle to gamepad. NULL if not available
     */
    HGamepad GetGamepad(uint8_t index);
    uint32_t GetGamepadButtonCount(HGamepad gamepad);
    uint32_t GetGamepadAxisCount(HGamepad gamepad);
    void GetGamepadDeviceName(HGamepad gamepad, const char** device_name);

    /**
     * Check if a keyboard is connected.
     * @return If a keyboard is connected or not
     */
    bool IsKeyboardConnected();

    /**
     * Check if a mouse is connected.
     * @return If a mouse is connected or not
     */
    bool IsMouseConnected();

    /**
     * Check if the supplied gamepad is connected or not.
     * @param gamepad Handle to gamepad
     * @return If the gamepad is connected or not
     */
    bool IsGamepadConnected(HGamepad gamepad);

    /**
     * Obtain a keyboard packet reflecting the current input state of a HID context.
     * @param packet Keyboard packet out argument
     * @return If the packet was successfully updated or not.
     */
    bool GetKeyboardPacket(KeyboardPacket* packet);

    /**
     * Obtain a mouse packet reflecting the current input state of a HID context.
     * @param packet Mouse packet out argument
     * @return If the packet was successfully updated or not.
     */
    bool GetMousePacket(MousePacket* packet);

    /**
     * Obtain a gamepad packet reflecting the current input state of the gamepad in a  HID context.
     * @param context HID context handle
     * @param packet Gamepad packet out argument
     * @return If the packet was successfully updated or not.
     */
    bool GetGamepadPacket(HGamepad gamepad, GamepadPacket* packet);

    /**
     * Convenience function to retrieve the state of a key from a keyboard packet.
     * @param packet Keyboard packet
     * @param key The requested key
     * @return If the key was pressed or not
     */
    bool GetKey(KeyboardPacket* packet, Key key);

    /**
     * Convenience function to set the state of a key from a keyboard packet.
     * @param packet Keyboard packet
     * @param key The requested key
     * @param value Key state
     */
    void SetKey(Key key, bool value);

    /**
     * Convenience function to retrieve the state of a mouse button from a mouse packet.
     * @param packet Mouse packet
     * @param button The requested button
     * @return If the button was pressed or not
     */
    bool GetMouseButton(MousePacket* packet, MouseButton button);

    /**
     * Convenience function to set the state of a mouse button from a mouse packet.
     * @param packet Mouse packet
     * @param button The requested button
     * @param value Button state
     */
    void SetMouseButton(MouseButton button, bool value);
    void SetMousePosition(int32_t x, int32_t y);
    void SetMouseWheel(int32_t value);

    /**
     * Convenience function to retrieve the state of a gamepad button from a gamepad packet.
     * @param packet Gamepad packet
     * @param button The requested button
     * @return If the button was pressed or not
     */
    bool GetGamepadButton(GamepadPacket* packet, uint32_t button);

    /**
     * Convenience function to set the state of a gamepad button from a gamepad packet.
     * @param packet Gamepad packet
     * @param button The requested button
     * @param value Button state
     */
    void SetGamepadButton(HGamepad gamepad, uint32_t button, bool value);
    void SetGamepadAxis(HGamepad gamepad, uint32_t axis, float value);

    /**
     * Get the name of a keyboard key.
     * @param key Keyboard key
     * @return The name of the key
     */
    const char* GetKeyName(Key key);

    /**
     * Get the name of a mouse button.
     * @param button Mouse button
     * @return The name of the button
     */
    const char* GetMouseButtonName(MouseButton button);
}

#endif // DM_HID_H
