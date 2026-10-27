use prost::Message;
use std::time::Duration;

use continuity_daemon::input::InputManager;
use continuity_daemon::proto::{
    input_message::Event,
    key_event::KeyState,
    mouse_button_event::{Button, State},
    InputMessage, KeyEvent, MouseButtonEvent, MouseMoveEvent, MouseScrollEvent,
};

#[tokio::test]
async fn test_wayland_input_manager_initialization() {
    let input_mgr = InputManager::new();
    // Allow background Wayland thread to query registry and bind zwlr_virtual_pointer_manager_v1
    tokio::time::sleep(Duration::from_millis(100)).await;

    // Verify wayland virtual pointer connected on Hyprland
    println!("Wayland Virtual Pointer active: {}", input_mgr.is_wayland_active());

    // Test mouse move
    input_mgr.handle_mouse_move(MouseMoveEvent { dx: 10.0, dy: -5.0 }).await;

    // Test mouse button click
    input_mgr.handle_mouse_button(MouseButtonEvent {
        button: Button::Left as i32,
        state: State::Click as i32,
    }).await;

    // Test mouse scroll
    input_mgr.handle_mouse_scroll(MouseScrollEvent { dx: 0.0, dy: 10.0 }).await;

    // Test key event
    input_mgr.handle_key_event(KeyEvent {
        key_name: "Shift_L".to_string(),
        text: String::new(),
        state: KeyState::KeyClick as i32,
        modifiers: vec![],
    }).await;
}

#[test]
fn test_input_protobuf_roundtrip_mouse_move() {
    let original = InputMessage {
        event: Some(Event::MouseMove(MouseMoveEvent {
            dx: 42.5,
            dy: -18.75,
        })),
    };

    let mut buf = Vec::new();
    original.encode(&mut buf).expect("encoding failed");

    let decoded = InputMessage::decode(&buf[..]).expect("decoding failed");
    if let Some(Event::MouseMove(m)) = decoded.event {
        assert_eq!(m.dx, 42.5);
        assert_eq!(m.dy, -18.75);
    } else {
        panic!("Decoded event did not match MouseMove");
    }
}

#[test]
fn test_input_protobuf_roundtrip_mouse_button() {
    let original = InputMessage {
        event: Some(Event::MouseButton(MouseButtonEvent {
            button: Button::Right as i32,
            state: State::Click as i32,
        })),
    };

    let mut buf = Vec::new();
    original.encode(&mut buf).expect("encoding failed");

    let decoded = InputMessage::decode(&buf[..]).expect("decoding failed");
    if let Some(Event::MouseButton(b)) = decoded.event {
        assert_eq!(b.button, Button::Right as i32);
        assert_eq!(b.state, State::Click as i32);
    } else {
        panic!("Decoded event did not match MouseButton");
    }
}

#[test]
fn test_input_protobuf_roundtrip_key_event() {
    let original = InputMessage {
        event: Some(Event::KeyEvent(KeyEvent {
            key_name: "Return".to_string(),
            text: "Hello Wayland".to_string(),
            state: KeyState::KeyClick as i32,
            modifiers: vec!["ctrl".to_string(), "alt".to_string()],
        })),
    };

    let mut buf = Vec::new();
    original.encode(&mut buf).expect("encoding failed");

    let decoded = InputMessage::decode(&buf[..]).expect("decoding failed");
    if let Some(Event::KeyEvent(k)) = decoded.event {
        assert_eq!(k.key_name, "Return");
        assert_eq!(k.text, "Hello Wayland");
        assert_eq!(k.state, KeyState::KeyClick as i32);
        assert_eq!(k.modifiers, vec!["ctrl", "alt"]);
    } else {
        panic!("Decoded event did not match KeyEvent");
    }
}
