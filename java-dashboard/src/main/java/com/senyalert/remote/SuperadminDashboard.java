package com.senyalert.remote;

import com.senyalert.model.EngineSettings;
import com.senyalert.service.ExportDefaults;
import com.senyalert.view.ui.BlueTheme;
import com.senyalert.view.ui.NativeFileDialogs;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

/** Separate developer workspace. Root must authenticate the local SUPERADMIN before opening it. */
public final class SuperadminDashboard extends JFrame {
    private final ClientRegistry registry;
    private final BiConsumer<String, JSONObject> audit;
    private final Map<String, RemoteClient> connections = new HashMap<>();
    private final DefaultListModel<ClientItem> clients = new DefaultListModel<>();
    private final JList<ClientItem> clientList = new JList<>(clients);
    private final JTextArea overview = area(false, "Last received client information. Timestamp shows when this local snapshot was received.");
    private final JTextArea settings = area(true, "Edit engine and camera JSON. Save as a template, export, or explicitly apply it to a connected client.");
    private final JTextArea users = area(false, "Last received users, roles, permission grants and cryptographic password hashes. Plaintext passwords are never retrieved.");
    private final JTextArea notes = area(true, "Private client notes saved only in the developer laptop's SQLite registry.");
    private final JComboBox<String> templates = new JComboBox<>();
    private final DefaultTableModel incidentModel = new DefaultTableModel(new String[]{"ID", "Camera", "Status", "Confidence", "Detected", "Note"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable incidents = new JTable(incidentModel);
    private final JLabel status = new JLabel("Select a saved client or connect using the client's support link and key.");
    private JSONArray storedTemplates = new JSONArray();
    private boolean busy;

    public SuperadminDashboard(Path sqlitePath, BiConsumer<String, JSONObject> audit) {
        super("SenyAlert Superadmin Dashboard");
        this.registry = new ClientRegistry(sqlitePath); this.audit = audit;
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 700)); setSize(1330, 850); setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(14, 14)); root.setBorder(BorderFactory.createEmptyBorder(20, 20, 14, 20));
        root.setBackground(BlueTheme.BACKGROUND); setContentPane(root);
        JLabel title = new JLabel("SenyAlert Superadmin Dashboard"); title.setFont(BlueTheme.font(Font.BOLD, 26));
        JPanel top = new JPanel(new BorderLayout()); top.add(title, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(button("Connect client", "Paste a client support link and that client's separately provisioned support key.", this::connect));
        buttons.add(button("Refresh client", "Retrieve and save a fresh client snapshot over the active support session.", this::refresh));
        buttons.add(button("Disconnect", "Forget this dashboard's connection credentials. The client can revoke its support session.", this::disconnect));
        top.add(buttons, BorderLayout.SOUTH); root.add(top, BorderLayout.NORTH);
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); clientList.setToolTipText("Choose a saved client; offline information remains available.");
        clientList.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) renderClient(); });
        JPanel sidebar = new JPanel(new BorderLayout(8, 8)); sidebar.setPreferredSize(new Dimension(250, 400));
        sidebar.add(new JLabel("Clients"), BorderLayout.NORTH); sidebar.add(new JScrollPane(clientList), BorderLayout.CENTER);
        sidebar.add(button("Add client manually", "Create a local client record before a support connection is available.", this::addClient), BorderLayout.SOUTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.setToolTipText("Select a client management section.");
        tabs.addTab(" Client overview", FontIcon.of(FontAwesomeSolid.DATABASE, 15, BlueTheme.PRIMARY), new JScrollPane(overview)); tabs.setToolTipTextAt(0, "Saved client snapshot and connection state.");
        tabs.addTab(" Settings & templates", FontIcon.of(FontAwesomeSolid.COGS, 15, BlueTheme.PRIMARY), settingsPanel()); tabs.setToolTipTextAt(1, "Configure engine and camera templates and apply them to a client.");
        tabs.addTab(" Users", FontIcon.of(FontAwesomeSolid.USERS, 15, BlueTheme.PRIMARY), usersPanel()); tabs.setToolTipTextAt(2, "Manage the connected client's accounts and view password hashes.");
        tabs.addTab(" Incidents", FontIcon.of(FontAwesomeSolid.BELL, 15, BlueTheme.PRIMARY), incidentsPanel()); tabs.setToolTipTextAt(3, "Acknowledge, resolve or annotate reported incidents.");
        JPanel notesPanel = new JPanel(new BorderLayout(8, 8)); notesPanel.add(new JScrollPane(notes));
        notesPanel.add(button("Save client notes", "Save these notes in this laptop's client registry and audit the change.", this::saveNotes), BorderLayout.SOUTH);
        tabs.addTab(" Client notes", FontIcon.of(FontAwesomeSolid.CLIPBOARD, 15, BlueTheme.PRIMARY), notesPanel); tabs.setToolTipTextAt(4, "Keep local support notes for the selected client.");
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, tabs); split.setResizeWeight(.21); split.setDividerLocation(270);
        split.setToolTipText("Drag the divider to adjust client list and details width."); root.add(split, BorderLayout.CENTER);
        status.setToolTipText("Result of the most recent support action."); root.add(status, BorderLayout.SOUTH);
        reloadClients(null); reloadTemplates(); settings.setText(EngineSettings.defaults().toPersistedJson().toString(2));
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { connections.clear(); }
        });
    }

    private JPanel settingsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JLabel("Engine and camera configuration JSON. Camera sources are interpreted on the client computer."), BorderLayout.NORTH);
        panel.add(new JScrollPane(settings), BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        templates.setToolTipText("Select a saved configuration template."); templates.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        controls.add(templates);
        controls.add(button("Load template", "Replace the editor with the selected saved template.", this::loadTemplate));
        controls.add(button("Save template", "Store the current engine and camera configuration as a reusable named template.", this::saveTemplate));
        controls.add(button("Import JSON", "Load an exported configuration file into the editor for review.", this::importSettings));
        controls.add(button("Export JSON", "Save the reviewed configuration to a file on this laptop for client setup.", this::exportSettings));
        controls.add(button("Apply to client", "Confirm and send the complete configuration to the connected client.", this::applySettings));
        panel.add(controls, BorderLayout.SOUTH); return panel;
    }

    private JPanel usersPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8)); panel.add(new JScrollPane(users));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(button("Create user", "Add an admin or user to the connected client.", () -> userAction("users.create")));
        controls.add(button("Edit access", "Change a client's user role, enabled state and individual permissions.", () -> userAction("users.update")));
        controls.add(button("Reset password", "Set a new password for a client account; passwords are hashed on the client.", () -> userAction("users.resetPassword")));
        panel.add(controls, BorderLayout.SOUTH); return panel;
    }

    private JPanel incidentsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8)); incidents.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        incidents.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); incidents.setToolTipText("Select a client incident to acknowledge, resolve or annotate.");
        for (int i = 0; i < incidentModel.getColumnCount(); i++) incidents.getColumnModel().getColumn(i).setPreferredWidth(i == 4 || i == 5 ? 230 : 130);
        panel.add(new JScrollPane(incidents)); JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(button("Acknowledge", "Mark the selected client incident as acknowledged.", () -> incidentAction("incident.acknowledge")));
        controls.add(button("Resolve", "Mark the selected client incident as resolved.", () -> incidentAction("incident.resolve")));
        controls.add(button("Edit note", "Update the selected incident's operator note with an audit record.", () -> incidentAction("incident.note")));
        controls.add(button("Create database backup", "Create a consistent SQLite backup in the client's fixed backup directory.", this::backup));
        panel.add(controls, BorderLayout.SOUTH); return panel;
    }

    private void connect() {
        JPasswordField link = new JPasswordField(42), key = new JPasswordField(42);
        link.setToolTipText("Paste the complete link received from the client, including its hidden fragment token.");
        key.setToolTipText("The separate support key provisioned on this client; never include it in the support link message.");
        JPanel fields = new JPanel(new GridLayout(0, 1, 4, 4)); fields.add(new JLabel("Client support link")); fields.add(link);
        fields.add(new JLabel("Client support key")); fields.add(key);
        if (JOptionPane.showConfirmDialog(this, fields, "Connect client", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        char[] linkValue = link.getPassword(), keyValue = key.getPassword();
        try {
            RemoteClient client = new RemoteClient(new String(linkValue), new String(keyValue));
            run("Connecting to client", () -> {
                audit.accept("superadmin.client.connect.requested", new JSONObject());
                JSONObject snapshot = client.snapshot(); registry.snapshot(snapshot); return snapshot;
            }, snapshot -> {
                connections.put(snapshot.getString("clientId"), client); reloadClients(snapshot.getString("clientId"));
                audit.accept("superadmin.client.connected", new JSONObject().put("clientId", snapshot.getString("clientId")));
            });
        } catch (Exception failure) { error(failure); }
        finally { java.util.Arrays.fill(linkValue, '\0'); java.util.Arrays.fill(keyValue, '\0'); link.setText(""); key.setText(""); }
    }

    private void refresh() {
        ClientItem item = selected(); if (item == null) return; RemoteClient client = connected(item); if (client == null) return;
        run("Refreshing client", () -> {
            audit.accept("superadmin.client.refresh.requested", new JSONObject().put("clientId", item.id()));
            JSONObject snapshot = client.snapshot(); registry.snapshot(snapshot); return snapshot;
        }, snapshot -> {
            audit.accept("superadmin.client.refreshed", new JSONObject().put("clientId", item.id()));
            reloadClients(item.id());
        });
    }

    private void disconnect() {
        ClientItem item = selected(); if (item == null) return;
        audit.accept("superadmin.client.disconnected", new JSONObject().put("clientId", item.id()));
        connections.remove(item.id()); renderClient(); status.setText("Disconnected. Ask the client to stop support to revoke its link.");
    }

    private void addClient() {
        String name = JOptionPane.showInputDialog(this, "Client name:"); if (name == null || name.isBlank()) return;
        String id = JOptionPane.showInputDialog(this,
                "Client ID (enter the client's configured SENYALERT_CLIENT_ID; SenyAlert does not generate one here):", "");
        if (id == null || id.isBlank()) return;
        try { audit.accept("superadmin.client.added", new JSONObject().put("clientId", id).put("name", name)); registry.add(id, name); reloadClients(id); }
        catch (Exception failure) { error(failure); }
    }

    private void saveNotes() {
        ClientItem item = selected(); if (item == null) return;
        try { audit.accept("superadmin.client.notes.changed", new JSONObject().put("clientId", item.id())
                    .put("before", item.data().optString("notes")).put("after", notes.getText()));
            registry.notes(item.id(), notes.getText()); reloadClients(item.id()); status.setText("Client notes saved."); }
        catch (Exception failure) { error(failure); }
    }

    private JSONObject reviewedSettings() {
        JSONObject json = new JSONObject(settings.getText());
        if (!json.has("cameras") || json.getJSONArray("cameras").isEmpty() || json.getJSONArray("cameras").length() > 4)
            throw new IllegalArgumentException("Configuration must contain one to four cameras.");
        // A client snapshot intentionally masks non-portable stream credentials. Validate the
        // rest of the structure here without replacing the masked value in the submitted JSON;
        // DashboardRemoteBackend restores only an existing client's matching source before its
        // authoritative validation and save.
        EngineSettings.fromJson(validationCopy(json));
        return json;
    }

    private static JSONObject validationCopy(JSONObject source) {
        JSONObject copy = new JSONObject(source.toString());
        replaceMaskedCameraSources(copy);
        return copy;
    }

    private static void replaceMaskedCameraSources(Object value) {
        if (value instanceof JSONObject object) {
            for (String key : object.keySet()) {
                Object child = object.get(key);
                if (child instanceof String text && "[private camera source]".equals(text)
                        && ("source".equals(key) || "camera_source".equals(key) || "source_url".equals(key))) {
                    object.put(key, "0");
                } else {
                    replaceMaskedCameraSources(child);
                }
            }
        } else if (value instanceof JSONArray array) {
            for (Object child : array) replaceMaskedCameraSources(child);
        }
    }

    private void applySettings() {
        try {
            JSONObject json = reviewedSettings();
            if (JOptionPane.showConfirmDialog(this, "Replace the connected client's engine and camera settings with this configuration?",
                    "Apply configuration", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            action("settings.update", new JSONObject().put("settings", json));
        } catch (Exception failure) { error(failure); }
    }

    private void importSettings() {
        Path path = NativeFileDialogs.openFile(this, "Import configuration template", ExportDefaults.Kind.CONFIGURATION,
                List.of("json")).orElse(null);
        if (path == null) return;
        try {
            if (Files.size(path) > 262_144) throw new IllegalArgumentException("Configuration is too large");
            JSONObject json = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
            if (json.has("settings")) json = json.getJSONObject("settings");
            audit.accept("superadmin.template.imported", new JSONObject().put("file", path.getFileName().toString()));
            settings.setText(json.toString(2)); status.setText("Configuration loaded for review. Use Apply to client to send it.");
        } catch (Exception failure) { error(failure); }
    }

    private void exportSettings() {
        try {
            JSONObject json = reviewedSettings();
            Path path = NativeFileDialogs.saveFile(this, "Export configuration template", ExportDefaults.Kind.CONFIGURATION,
                    "senyalert-config.json", List.of("json")).orElse(null);
            if (path == null) return;
            if (Files.exists(path)) throw new IllegalArgumentException("Choose a new export filename; existing files are preserved.");
            audit.accept("superadmin.template.export.requested", new JSONObject().put("file", path.getFileName().toString()));
            Files.writeString(path, json.toString(2), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            audit.accept("superadmin.template.exported", new JSONObject().put("file", path.getFileName().toString()));
            status.setText("Configuration exported to " + path.getFileName());
        } catch (Exception failure) { error(failure); }
    }

    private void saveTemplate() {
        try {
            JSONObject json = reviewedSettings(); String name = JOptionPane.showInputDialog(this, "Template name:"); if (name == null || name.isBlank()) return;
            boolean existing = false; for (int i = 0; i < storedTemplates.length(); i++) if (name.trim().equals(storedTemplates.getJSONObject(i).getString("name"))) existing = true;
            if (existing && JOptionPane.showConfirmDialog(this, "Replace this saved template?", "Save template", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            audit.accept("superadmin.template.saved", new JSONObject().put("name", name).put("cameraCount", json.getJSONArray("cameras").length()));
            registry.template(name, json); reloadTemplates(); templates.setSelectedItem(name.trim()); status.setText("Template saved.");
        } catch (Exception failure) { error(failure); }
    }

    private void loadTemplate() {
        int index = templates.getSelectedIndex(); if (index < 0) return;
        settings.setText(storedTemplates.getJSONObject(index).getJSONObject("settings").toString(2));
        status.setText("Template loaded in editor. Review before applying to a client.");
    }

    private void userAction(String operation) {
        if (selected() == null) return;
        JTextField username = new JTextField(24), permissions = new JTextField(36);
        JComboBox<String> role = new JComboBox<>(new String[]{"USER", "ADMIN"});
        JComboBox<String> enabled = new JComboBox<>(new String[]{"Enabled", "Disabled"});
        JPasswordField password = new JPasswordField(24);
        username.setToolTipText("Exact client account username. The reserved superadmin account cannot be created remotely.");
        permissions.setToolTipText("Comma-separated permission names shown in the user snapshot, for example VIEW_INCIDENTS, ACKNOWLEDGE_INCIDENTS.");
        role.setToolTipText("Admins have full local access. Users receive only the selected permission names.");
        enabled.setToolTipText("Disabled accounts cannot sign in."); password.setToolTipText("New password. The client stores a salted password hash.");
        role.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); enabled.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8)); form.add(new JLabel("Username")); form.add(username);
        if (!operation.equals("users.resetPassword")) {
            form.add(new JLabel("Role")); form.add(role); form.add(new JLabel("Permissions (comma separated)")); form.add(permissions);
            form.add(new JLabel("Account state")); form.add(enabled);
        }
        if (!operation.equals("users.update")) { form.add(new JLabel("New password")); form.add(password); }
        if (JOptionPane.showConfirmDialog(this, form, operation, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        JSONObject payload = new JSONObject().put("username", username.getText().trim());
        if (!operation.equals("users.resetPassword")) {
            JSONArray grants = new JSONArray(); for (String permission : permissions.getText().split(",")) if (!permission.isBlank()) grants.put(permission.trim());
            payload.put("role", role.getSelectedItem()).put("permissions", grants).put("enabled", enabled.getSelectedIndex() == 0);
        }
        char[] entered = password.getPassword();
        if (!operation.equals("users.update")) payload.put("password", new String(entered));
        java.util.Arrays.fill(entered, '\0'); password.setText(""); action(operation, payload);
    }

    private void incidentAction(String operation) {
        int row = incidents.getSelectedRow(); if (row < 0) { status.setText("Select an incident first."); return; }
        Object rawId = incidentModel.getValueAt(incidents.convertRowIndexToModel(row), 0);
        JSONObject payload = new JSONObject().put("id", rawId);
        if (operation.equals("incident.note")) {
            String value = JOptionPane.showInputDialog(this, "Operator note:", incidentModel.getValueAt(row, 5)); if (value == null) return;
            payload.put("note", value);
        }
        action(operation, payload);
    }

    private void backup() {
        if (JOptionPane.showConfirmDialog(this, "Create a SQLite backup on the client computer?", "Database backup", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            action("database.backup", new JSONObject());
    }

    private void action(String operation, JSONObject payload) {
        ClientItem item = selected(); if (item == null) return; RemoteClient client = connected(item); if (client == null) return;
        run(operation, () -> {
            audit.accept("superadmin.action.requested", new JSONObject().put("clientId", item.id()).put("operation", operation));
            JSONObject result = client.execute(operation, payload); JSONObject snapshot = client.snapshot(); registry.snapshot(snapshot);
            audit.accept("superadmin.action.completed", new JSONObject().put("clientId", item.id()).put("operation", operation)); return result;
        }, result -> { reloadClients(item.id()); status.setText("Completed: " + operation + ". " + result.optString("message", "")); });
    }

    private void reloadClients(String selectId) {
        try {
            String keep = selectId != null ? selectId : clientList.getSelectedValue() == null ? null : clientList.getSelectedValue().id();
            clients.clear(); JSONArray rows = registry.clients(); int selected = -1;
            for (int i = 0; i < rows.length(); i++) { ClientItem item = new ClientItem(rows.getJSONObject(i)); clients.addElement(item); if (item.id().equals(keep)) selected = i; }
            if (selected >= 0) clientList.setSelectedIndex(selected); else if (!clients.isEmpty()) clientList.setSelectedIndex(0);
        } catch (Exception failure) { error(failure); }
    }

    private void reloadTemplates() {
        try { storedTemplates = registry.templates(); DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            for (int i = 0; i < storedTemplates.length(); i++) model.addElement(storedTemplates.getJSONObject(i).getString("name")); templates.setModel(model); }
        catch (Exception failure) { error(failure); }
    }

    private void renderClient() {
        ClientItem item = clientList.getSelectedValue(); if (item == null) return;
        JSONObject saved = item.data().optJSONObject("snapshot"); if (saved == null) saved = new JSONObject();
        overview.setText("Client: " + item + "\nID: " + item.id() + "\nLast received: " + item.data().optString("receivedAt")
                + "\nConnection: " + (connections.containsKey(item.id()) ? "Session available; refresh to verify" : "Offline saved snapshot")
                + "\n\nDatabase\n" + saved.optJSONObject("database") + "\n\nClient information\n" + saved.optJSONObject("client"));
        JSONObject config = saved.optJSONObject("settings"); if (config != null) settings.setText(config.toString(2));
        JSONArray accounts = saved.optJSONArray("users"); users.setText(accounts == null ? "No client snapshot received." : accounts.toString(2));
        notes.setText(item.data().optString("notes")); incidentModel.setRowCount(0);
        JSONArray records = saved.optJSONArray("incidents");
        if (records != null) for (int i = 0; i < records.length(); i++) {
            JSONObject incident = records.getJSONObject(i); incidentModel.addRow(new Object[]{
                    incident.opt("id") == null ? incident.opt("incidentId") : incident.opt("id"),
                    incident.optString("cameraId", incident.optString("camera_id")), incident.optString("status"),
                    incident.opt("confidence"), incident.optString("timestamp", incident.optString("detectedAt")),
                    incident.optString("note", incident.optString("operatorNote"))});
        }
        overview.setCaretPosition(0); users.setCaretPosition(0); settings.setCaretPosition(0);
    }

    private ClientItem selected() { ClientItem item = clientList.getSelectedValue(); if (item == null) status.setText("Select or add a client first."); return item; }
    private RemoteClient connected(ClientItem item) { RemoteClient client = connections.get(item.id()); if (client == null) status.setText("Connect this client before changing its settings or records."); return client; }
    private void run(String label, Work work, java.util.function.Consumer<JSONObject> success) {
        if (busy) { status.setText("Wait for the current support operation to finish."); return; } busy = true; status.setText(label + "...");
        new SwingWorker<JSONObject, Void>() {
            @Override protected JSONObject doInBackground() throws Exception { return work.run(); }
            @Override protected void done() { busy = false; try { JSONObject result = get(); status.setText(label + " completed."); success.accept(result); }
                catch (Exception failure) { Throwable cause = failure.getCause() == null ? failure : failure.getCause(); error(cause); } }
        }.execute();
    }
    private void error(Throwable failure) { status.setText("Operation failed."); JOptionPane.showMessageDialog(this, failure.getMessage(), "Support action", JOptionPane.ERROR_MESSAGE); }
    private static JTextArea area(boolean editable, String tooltip) { JTextArea area = new JTextArea(); area.setEditable(editable); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14)); area.setToolTipText(tooltip); area.setMargin(new java.awt.Insets(12, 12, 12, 12)); return area; }
    private static JButton button(String text, String tooltip, Runnable action) { JButton button = new JButton(text); button.setToolTipText(tooltip); button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); button.addActionListener(event -> action.run()); return button; }
    private record ClientItem(JSONObject data) { String id() { return data.getString("clientId"); } @Override public String toString() { return data.getString("clientName"); } }
    @FunctionalInterface private interface Work { JSONObject run() throws Exception; }
}
