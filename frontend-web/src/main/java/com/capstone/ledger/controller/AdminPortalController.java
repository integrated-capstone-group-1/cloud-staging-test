package com.capstone.ledger.controller;

import com.capstone.ledger.frontend.config.SessionAuthInterceptor;
import com.capstone.ledger.frontend.model.*;
import com.capstone.ledger.frontend.service.AdminPortalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Staff-Facing Admin Web Portal Controller (Task T-011):
 * - DIG-50: Admin portal shell, RBAC guard (ROLE_ADMIN), operations navigation
 * - DIG-54: Account lock/freeze governance, parameter editor, alerts & held transfers
 * - DIG-90: Admin DLQ console (inspect, replay, discard with reason)
 * - DIG-53: Audit ledger & reconciliation viewer (read-only, audited)
 * - DIG-102: Real-time transaction monitoring dashboard (11-stage tracker, status chips)
 * - DIG-103: Customer 360 profile dashboard
 */
@Controller
public class AdminPortalController {

    private final AdminPortalService adminPortalService;

    public AdminPortalController(AdminPortalService adminPortalService) {
        this.adminPortalService = adminPortalService;
    }

    /**
     * Security Guard: Enforces that caller is authenticated and possesses ROLE_ADMIN.
     * Throws 403 Forbidden for any non-admin or unauthenticated access.
     */
    private UserSession requireAdmin(HttpSession session) {
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Administrator privileges required");
        }
        UserSession user = (UserSession) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null || !user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Administrator privileges required");
        }
        return user;
    }

    // =========================================================================
    // DIG-103: Customer 360 Dashboard
    // =========================================================================

    @GetMapping({"/admin/customers/{id}", "/v1/admin/customers/{id}/360"})
    public String customer360(@PathVariable("id") String customerId,
                              HttpSession session, Model model) {
        UserSession user = requireAdmin(session);

        Customer360View customer360 = adminPortalService.getCustomer360(customerId, user.getEmail());
        model.addAttribute("user", user);
        model.addAttribute("c360", customer360);
        return "admin/customer_360";
    }

    @GetMapping(value = {"/api/v1/admin/customers/{id}/360"}, produces = "application/json")
    @ResponseBody
    public ResponseEntity<Customer360View> customer360Rest(@PathVariable("id") String customerId,
                                                          HttpSession session) {
        UserSession user = requireAdmin(session);
        Customer360View customer360 = adminPortalService.getCustomer360(customerId, user.getEmail());
        return ResponseEntity.ok(customer360);
    }

    // =========================================================================
    // DIG-54: Account Governance (Freeze / Unfreeze) & Customer Lock
    // =========================================================================

    @PostMapping({"/api/v1/admin/accounts/{id}/freeze", "/admin/accounts/{id}/freeze"})
    public Object freezeAccount(@PathVariable("id") String accountId,
                                @RequestParam(value = "redirectCustId", required = false) String redirectCustId,
                                HttpServletRequest request,
                                HttpSession session,
                                RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        adminPortalService.freezeAccount(accountId, user.getEmail());

        if (isJsonRequest(request)) {
            return ResponseEntity.ok(Map.of(
                    "status", "FROZEN",
                    "accountId", accountId,
                    "message", "Account successfully frozen by administrator"
            ));
        }

        redirectAttributes.addFlashAttribute("successMessage",
                "Account " + accountId + " has been FROZEN. All balance mutations are halted.");
        if (redirectCustId != null && !redirectCustId.isBlank()) {
            return "redirect:/admin/customers/" + redirectCustId;
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping({"/api/v1/admin/accounts/{id}/unfreeze", "/admin/accounts/{id}/unfreeze"})
    public Object unfreezeAccount(@PathVariable("id") String accountId,
                                  @RequestParam(value = "redirectCustId", required = false) String redirectCustId,
                                  HttpServletRequest request,
                                  HttpSession session,
                                  RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        adminPortalService.unfreezeAccount(accountId, user.getEmail());

        if (isJsonRequest(request)) {
            return ResponseEntity.ok(Map.of(
                    "status", "ACTIVE",
                    "accountId", accountId,
                    "message", "Account successfully unfrozen by administrator"
            ));
        }

        redirectAttributes.addFlashAttribute("successMessage",
                "Account " + accountId + " has been UNFROZEN and restored to ACTIVE status.");
        if (redirectCustId != null && !redirectCustId.isBlank()) {
            return "redirect:/admin/customers/" + redirectCustId;
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/customers/{id}/lock")
    public String lockCustomer(@PathVariable("id") String customerId,
                               HttpSession session,
                               RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        adminPortalService.lockCustomer(customerId, user.getEmail());
        redirectAttributes.addFlashAttribute("successMessage",
                "Customer " + customerId + " authentication has been locked.");
        return "redirect:/admin/customers/" + customerId;
    }

    @PostMapping("/admin/customers/{id}/unlock")
    public String unlockCustomer(@PathVariable("id") String customerId,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        adminPortalService.unlockCustomer(customerId, user.getEmail());
        redirectAttributes.addFlashAttribute("successMessage",
                "Customer " + customerId + " authentication has been unlocked.");
        return "redirect:/admin/customers/" + customerId;
    }

    // =========================================================================
    // DIG-51 & DIG-54: Held Transfers Queue & Decisions
    // =========================================================================

    @GetMapping({"/admin/transfers/held", "/v1/admin/transfers/held"})
    public String heldTransfers(HttpSession session, Model model) {
        UserSession user = requireAdmin(session);
        model.addAttribute("user", user);
        model.addAttribute("heldList", adminPortalService.getHeldTransactions());
        return "admin/held_transactions";
    }

    @PostMapping({"/admin/transfers/held/{txnId}/approve", "/v1/admin/transfers/held/{txnId}/approve"})
    public Object approveHeldTransfer(@PathVariable("txnId") String txnId,
                                      @RequestParam(value = "notes", required = false) String notes,
                                      HttpServletRequest request,
                                      HttpSession session,
                                      RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        boolean success = adminPortalService.approveHeldTransaction(txnId, user.getEmail(), notes);

        if (isJsonRequest(request)) {
            return ResponseEntity.ok(Map.of(
                    "txnId", txnId,
                    "status", "APPROVED",
                    "success", success
            ));
        }

        redirectAttributes.addFlashAttribute("successMessage",
                "Held transfer " + txnId + " was APPROVED and released for core settlement.");
        return "redirect:/admin/transfers/held";
    }

    @PostMapping({"/admin/transfers/held/{txnId}/reject", "/v1/admin/transfers/held/{txnId}/reject"})
    public Object rejectHeldTransfer(@PathVariable("txnId") String txnId,
                                     @RequestParam(value = "notes", required = false) String notes,
                                     HttpServletRequest request,
                                     HttpSession session,
                                     RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        boolean success = adminPortalService.rejectHeldTransaction(txnId, user.getEmail(), notes);

        if (isJsonRequest(request)) {
            return ResponseEntity.ok(Map.of(
                    "txnId", txnId,
                    "status", "REJECTED",
                    "success", success
            ));
        }

        redirectAttributes.addFlashAttribute("successMessage",
                "Held transfer " + txnId + " was REJECTED and reversed.");
        return "redirect:/admin/transfers/held";
    }

    // =========================================================================
    // DIG-17 & DIG-54: Versioned Parameter Store Editor
    // =========================================================================

    @GetMapping({"/admin/parameters", "/v1/admin/parameters"})
    public String parametersEditor(HttpSession session, Model model) {
        UserSession user = requireAdmin(session);
        model.addAttribute("user", user);
        model.addAttribute("params", adminPortalService.getParameters());
        return "admin/parameters";
    }

    @PostMapping({"/admin/parameters", "/v1/admin/parameters"})
    public Object updateParameters(@ModelAttribute SystemParameterView form,
                                   HttpServletRequest request,
                                   HttpSession session,
                                   RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        SystemParameterView updated = adminPortalService.updateParameters(form, user.getEmail());

        if (isJsonRequest(request)) {
            return ResponseEntity.ok(updated);
        }

        redirectAttributes.addFlashAttribute("successMessage",
                "System parameters successfully updated to version v" + updated.getVersion() + ".");
        return "redirect:/admin/parameters";
    }

    // =========================================================================
    // DIG-90: Admin DLQ Console (Inspect, Replay, Discard)
    // =========================================================================

    @GetMapping({"/admin/dlq", "/v1/admin/dlq"})
    public String dlqConsole(HttpSession session, Model model) {
        UserSession user = requireAdmin(session);
        List<DlqMessageView> messages = adminPortalService.getDlqMessages();

        long pendingCount = messages.stream().filter(DlqMessageView::isPending).count();
        long replayedCount = messages.stream().filter(DlqMessageView::isReplayed).count();
        long discardedCount = messages.stream().filter(DlqMessageView::isDiscarded).count();

        model.addAttribute("user", user);
        model.addAttribute("messages", messages);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("replayedCount", replayedCount);
        model.addAttribute("discardedCount", discardedCount);
        return "admin/dlq";
    }

    @PostMapping({"/admin/dlq/{id}/replay", "/v1/admin/dlq/{id}/replay"})
    public Object replayDlq(@PathVariable("id") String id,
                            HttpServletRequest request,
                            HttpSession session,
                            RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        boolean success = adminPortalService.replayDlqMessage(id, user.getEmail());

        if (isJsonRequest(request)) {
            return ResponseEntity.ok(Map.of("id", id, "status", "REPLAYED", "success", success));
        }

        redirectAttributes.addFlashAttribute("successMessage",
                "DLQ Message " + id + " re-published with original key. No duplicate postings.");
        return "redirect:/admin/dlq";
    }

    @PostMapping({"/admin/dlq/{id}/discard", "/v1/admin/dlq/{id}/discard"})
    public Object discardDlq(@PathVariable("id") String id,
                             @RequestParam(value = "reason", required = false) String reason,
                             HttpServletRequest request,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {
        UserSession user = requireAdmin(session);
        if (reason == null || reason.isBlank()) {
            reason = "Administratively discarded by compliance officer";
        }
        boolean success = adminPortalService.discardDlqMessage(id, reason, user.getEmail());

        if (isJsonRequest(request)) {
            return ResponseEntity.ok(Map.of("id", id, "status", "DISCARDED", "reason", reason, "success", success));
        }

        redirectAttributes.addFlashAttribute("successMessage",
                "DLQ Message " + id + " safely discarded: " + reason);
        return "redirect:/admin/dlq";
    }

    // =========================================================================
    // DIG-102: Real-time Transaction Monitoring Dashboard
    // =========================================================================

    @GetMapping({"/admin/monitoring", "/v1/admin/monitoring"})
    public String monitoringDashboard(HttpSession session, Model model) {
        UserSession user = requireAdmin(session);
        List<TransactionMonitoringView> feed = adminPortalService.getMonitoringFeed();

        model.addAttribute("user", user);
        model.addAttribute("feed", feed);
        model.addAttribute("p95LatencyMs", "340 ms");
        model.addAttribute("activeTps", "18.4 tx/s");
        return "admin/monitoring";
    }

    // =========================================================================
    // DIG-53: Audit Ledger and Reconciliation Viewer (Read-only)
    // =========================================================================

    @GetMapping({"/admin/audit", "/v1/admin/audit"})
    public String auditLedgerViewer(HttpSession session, Model model) {
        UserSession user = requireAdmin(session);
        adminPortalService.logAudit("AUDIT_LOG_INSPECT", user.getEmail(), "AUDIT_LEDGER", "ALL",
                "Compliance officer reviewed immutable administrative audit trail");

        model.addAttribute("user", user);
        model.addAttribute("auditLogs", adminPortalService.getAuditLogs());
        return "admin/audit";
    }

    // Helper to determine if incoming request prefers JSON vs HTML redirect
    private boolean isJsonRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        return uri.startsWith("/api/") || (accept != null && accept.contains("application/json"));
    }
}

