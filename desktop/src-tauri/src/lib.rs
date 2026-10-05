// The UI in ../src talks to the Spring Boot backend over HTTP with fetch(), so no Rust
// commands are needed yet. Add them here with .invoke_handler(...) if that changes.

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running the Inventix desktop app");
}
