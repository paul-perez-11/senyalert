package com.senyalert;

/**
 * Compatibility facade for callers that used the original package. The active
 * dashboard lives in com.senyalert.view and contains presentation code only.
 */
@Deprecated
public class DashboardFrame extends com.senyalert.view.DashboardFrame {
    public DashboardFrame() {
        super();
    }
}
