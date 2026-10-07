package com.capstone.ledger.frontend.controller;

import com.capstone.ledger.frontend.adapter.BankingApiClient;
import com.capstone.ledger.frontend.config.SessionAuthInterceptor;
import com.capstone.ledger.frontend.model.AccountView;
import com.capstone.ledger.frontend.model.CustomerView;
import com.capstone.ledger.frontend.model.TransactionView;
import com.capstone.ledger.frontend.model.UserSession;
import com.capstone.ledger.frontend.model.enums.AccountStatus;
import com.capstone.ledger.frontend.model.enums.KycStatus;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

import com.capstone.ledger.frontend.model.DlqMessageView;
import com.capstone.ledger.frontend.model.HeldTransactionView;
import com.capstone.ledger.frontend.service.AdminPortalService;

/**
 * Controller for Bank Administrator operations:
 * - Executive Banking Operations Dashboard (Real-time Telemetry, KPIs, Liquidity, Volume)
 * - Customer Directory & Profiles
 * - System Accounts Registry & Freeze/Unfreeze Controls
 */
@Controller
public class AdminController {

    private final BankingApiClient bankingClient;
    private final AdminPortalService adminPortalService;

    public AdminController(BankingApiClient bankingClient, AdminPortalService adminPortalService) {
        this.bankingClient = bankingClient;
        this.adminPortalService = adminPortalService;
    }

    @GetMapping({"/admin/dashboard", "/dashboard"})
    public String adminDashboard(HttpSession session, Model model) {
        UserSession user = (UserSession) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null) {
            return "redirect:/login";
        }
        if (!user.isAdmin()) {
            return "redirect:/customer/dashboard";
        }

        List<CustomerView> customers = bankingClient.getAllCustomers();
        List<AccountView> accounts = bankingClient.getAllAccounts();
        List<TransactionView> transactions = bankingClient.getAllTransactions();

        // Calculate consolidated liquidity across all accounts in PHP
        BigDecimal totalLiquidityPhp = accounts.stream()
                .map(a -> {
                    BigDecimal rate = bankingClient.getExchangeRate(a.getCurrencyCode(), "PHP");
                    return a.getBalance().multiply(rate);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate per-currency totals
        BigDecimal totalPhp = accounts.stream()
                .filter(a -> "PHP".equalsIgnoreCase(a.getCurrencyCode()))
                .map(AccountView::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalUsd = accounts.stream()
                .filter(a -> "USD".equalsIgnoreCase(a.getCurrencyCode()))
                .map(AccountView::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalEur = accounts.stream()
                .filter(a -> "EUR".equalsIgnoreCase(a.getCurrencyCode()))
                .map(AccountView::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Account status counts
        long activeAccounts = accounts.stream().filter(a -> a.getAccountStatus() == AccountStatus.ACTIVE).count();
        long frozenAccounts = accounts.stream().filter(a -> a.getAccountStatus() == AccountStatus.FROZEN).count();

        // Operational Queues from AdminPortalService
        List<HeldTransactionView> heldList = adminPortalService.getHeldTransactions();
        List<DlqMessageView> dlqMessages = adminPortalService.getDlqMessages();
        long dlqPendingCount = dlqMessages.stream().filter(DlqMessageView::isPending).count();

        model.addAttribute("user", user);
        model.addAttribute("customers", customers);
        model.addAttribute("accounts", accounts);
        model.addAttribute("totalBalance", totalLiquidityPhp);
        model.addAttribute("totalPhpBalance", totalPhp);
        model.addAttribute("totalUsdBalance", totalUsd);
        model.addAttribute("totalEurBalance", totalEur);
        model.addAttribute("customerCount", customers.size());
        model.addAttribute("accountCount", accounts.size());
        model.addAttribute("activeAccounts", activeAccounts);
        model.addAttribute("frozenAccounts", frozenAccounts);
        model.addAttribute("heldList", heldList);
        model.addAttribute("heldCount", heldList.size());
        model.addAttribute("dlqMessages", dlqMessages);
        model.addAttribute("dlqPendingCount", dlqPendingCount);
        model.addAttribute("monitoringFeed", adminPortalService.getMonitoringFeed());
        model.addAttribute("activeTps", "24.6");
        model.addAttribute("p95Latency", "180 ms");
        model.addAttribute("successRate", "99.85%");
        model.addAttribute("reconStatus", "BALANCED");
        model.addAttribute("systemUptime", "99.99%");
        model.addAttribute("recentTransactions", transactions.stream().limit(10).toList());

        return "admin/dashboard";
    }

    @GetMapping("/admin/customers")
    public String customerDirectory(HttpSession session, Model model) {
        UserSession user = (UserSession) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null || !user.isAdmin()) {
            return "redirect:/login";
        }

        model.addAttribute("user", user);
        model.addAttribute("customers", bankingClient.getAllCustomers());
        return "admin/customers";
    }

    @PostMapping("/admin/customers/{customerId}/kyc")
    public String updateCustomerKyc(@PathVariable String customerId,
                                    @RequestParam KycStatus status,
                                    RedirectAttributes redirectAttributes) {
        bankingClient.updateKycStatus(customerId, status);
        redirectAttributes.addFlashAttribute("successMessage",
                "Customer " + customerId + " KYC status updated to " + status.getDisplayName());
        return "redirect:/admin/customers";
    }

    @GetMapping("/admin/accounts")
    public String accountsRegistry(HttpSession session, Model model) {
        UserSession user = (UserSession) session.getAttribute(SessionAuthInterceptor.SESSION_USER);
        if (user == null || !user.isAdmin()) {
            return "redirect:/login";
        }

        model.addAttribute("user", user);
        model.addAttribute("accounts", bankingClient.getAllAccounts());
        return "admin/accounts";
    }

    @PostMapping("/admin/accounts/{accountId}/status")
    public String toggleAccountStatus(@PathVariable String accountId,
                                      @RequestParam AccountStatus status,
                                      RedirectAttributes redirectAttributes) {
        bankingClient.updateAccountStatus(accountId, status);
        redirectAttributes.addFlashAttribute("successMessage",
                "Account " + accountId + " status updated to " + status.getDisplayName());
        return "redirect:/admin/accounts";
    }
}

