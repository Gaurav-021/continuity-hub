use continuity_daemon::camera::sidecar::SidecarManager;

#[tokio::test]
async fn test_sidecar_manager_lifecycle_and_state() {
    let sidecar = SidecarManager::new();
    assert!(!sidecar.is_active());
    assert_eq!(sidecar.get_workspace_id().await, None);

    // Verify touch click doesn't crash even if inactive
    sidecar.handle_touch_click(0.5, 0.5).await;

    // Verify stopping inactive sidecar is a clean no-op
    sidecar.stop_sidecar().await;
    assert!(!sidecar.is_active());
}
