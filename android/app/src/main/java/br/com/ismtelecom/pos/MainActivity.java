package br.com.ismtelecom.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.graphics.Paint;
import android.content.ActivityNotFoundException;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Telas de atendimento ISM para terminais POS; sem integração proprietária de impressora. */
public class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(8, 58, 112);
    private static final int CYAN = Color.rgb(29, 162, 215);
    private static final int PALE = Color.rgb(242, 248, 252);
    private static final int MUTED = Color.rgb(103, 126, 143);
    private static final int GREEN = Color.rgb(58, 169, 109);
    private static final int RED = Color.rgb(202, 66, 103);
    private static final long SESSION_MILLIS = 300_000L; // Mesmo TTL do backend: 5 minutos.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // O cadastro de terminal nunca deve bloquear a fila de consultas de CPF/PIX.
    private final ExecutorService registryExecutor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView sessionFooter;
    private String session = "";
    private JSONObject invoiceResponse;
    private String screenType = "home";
    private int filterMode = 0; // 0 = todas, 1 = em aberto, 2 = vencidas.
    private long deadline = 0;
    private int requestEpoch = 0;
    private boolean loading = false;
    private File tempPdf;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pdfDescriptor;
    private int currentPage = 0;
    private String pixWatchingInvoiceId = "";
    private String currentPaymentInvoiceId = "";
    private TextView pixStatus;

    private final Runnable pixAutoCheck = new Runnable() {
        @Override public void run() {
            if (!"payment".equals(screenType) || pixWatchingInvoiceId.isEmpty()
                    || !isSessionValid()) return;
            if (!loading) checkPixPayment(pixWatchingInvoiceId, true);
            ui.postDelayed(this, 8000);
        }
    };

    private void stopPixAutoCheck() {
        ui.removeCallbacks(pixAutoCheck);
        pixWatchingInvoiceId = "";
        pixStatus = null;
    }

    private final Runnable sessionTick = new Runnable() {
        @Override public void run() {
            if (deadline == 0) return;
            long left = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
            if (sessionFooter != null) sessionFooter.setText("Fim da sessão: " + left + " segundos");
            if (left == 0) {
                showHome();
                Toast.makeText(MainActivity.this,
                        "Sessão encerrada. Consulte o CPF novamente.",
                        Toast.LENGTH_LONG).show();
            } else {
                ui.postDelayed(this, 1000);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (terminalConfigured()) showHome();
        else showTerminalSetup();
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + .5f);
    }

    private GradientDrawable shape(int color, int corner, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(corner));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private LinearLayout col() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String value, int size, int color, boolean strong) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (strong) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private Button button(String text, int color, boolean filled) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(9), dp(8), dp(9), dp(8));
        b.setTextColor(filled ? Color.WHITE : color);
        b.setBackground(shape(filled ? color : Color.WHITE, 12, filled ? 0 : color));
        return b;
    }

    private void primary(String text, View.OnClickListener click) {
        Button b = button(text, CYAN, true);
        b.setOnClickListener(click);
        content.addView(b, margins(-1, dp(53), 12));
    }

    /** Logo oficial: proporção original e sem desenho manual inclinado. */
    private View logo(int height) {
        ImageView image = new ImageView(this);
        image.setImageBitmap(IsmOfficialLogo.decode(this));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(false);
        image.setContentDescription("ISM Telecom - 100% Fibra Óptica");
        image.setPadding(dp(3), dp(3), dp(3), dp(3));
        return image;
    }

    private void initScreen(String title, String subtitle, boolean back) {
        LinearLayout root = col();
        root.setBackgroundColor(PALE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = col();
        content.setPadding(dp(18), dp(12), dp(18), dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (back) {
            TextView goBack = label("← Voltar", 13, NAVY, true);
            goBack.setGravity(Gravity.CENTER);
            goBack.setContentDescription("Voltar para a tela anterior");
            goBack.setOnClickListener(v -> backToInvoicesOrHome());
            header.addView(goBack, new LinearLayout.LayoutParams(dp(79), dp(58)));
        }
        // A logo da HOME fica menor; outras telas mantêm 72dp.
        View brand = logo(back ? 72 : 112);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                0, dp(back ? 72 : 112), 1);
        header.addView(brand, logoParams);
        if (back) {
            TextView home = label("⌂ Início", 13, NAVY, true);
            home.setGravity(Gravity.CENTER);
            home.setContentDescription("Voltar à tela inicial sem encerrar o aplicativo");
            home.setOnClickListener(v -> showHome());
            header.addView(home, new LinearLayout.LayoutParams(dp(79), dp(58)));
        }
        content.addView(header, margins(-1, -2, back ? 10 : 18));

        if (!title.isEmpty()) {
            TextView heading = label(title, 23, NAVY, true);
            content.addView(heading, margins(-1, -2, 6));
        }
        if (!subtitle.isEmpty()) {
            TextView sub = label(subtitle, 14, MUTED, false);
            content.addView(sub, margins(-1, -2, 18));
        }

        // Rodapé fixo somente na tela inicial, conforme referência da ISM.
        if (!back && title.isEmpty() && subtitle.isEmpty()) {
            LinearLayout footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setBackgroundColor(Color.rgb(222, 234, 245));
            footer.setPadding(dp(12), dp(12), dp(12), dp(12));

            TextView assistance = label(
                    "Importante:\nCaso ocorra algum problema durante a utilização, "
                    + "entre em contato com nosso atendimento (71) 3334-0606.",
                    11, NAVY, false);
            TextView touch = label(
                    "☝ Tela sensível ao toque.\nToque para escolher a opção desejada.",
                    11, NAVY, false);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
            right.leftMargin = dp(15);
            footer.addView(assistance, left);
            footer.addView(touch, right);
            root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        }

        sessionFooter = null;
        if (deadline > 0) {
            sessionFooter = label("", 11, MUTED, false);
            sessionFooter.setGravity(Gravity.RIGHT);
            sessionFooter.setPadding(dp(16), dp(9), dp(16), dp(9));
            sessionFooter.setBackgroundColor(Color.WHITE);
            root.addView(sessionFooter, new LinearLayout.LayoutParams(-1, dp(34)));
            long left = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
            sessionFooter.setText("Fim da sessão: " + left + " segundos");
        }
        setContentView(root);
    }

    private void backToInvoicesOrHome() {
        switch (screenType) {
            case "payment":
                if (invoiceResponse != null && isSessionValid()
                        && !currentPaymentInvoiceId.isEmpty())
                    showPaymentOptions(currentPaymentInvoiceId);
                else if (invoiceResponse != null && isSessionValid()) renderInvoices();
                else showHome();
                break;
            case "payment-options":
                if (invoiceResponse != null && isSessionValid()) renderInvoices();
                else showHome();
                break;
            case "invoices":
                showLookup();
                break;
            case "pix-history":
                if (invoiceResponse != null && isSessionValid()) renderInvoices();
                else showLookup();
                break;
            case "receipt":
                if (invoiceResponse != null && isSessionValid()) renderInvoices();
                else showLookup();
                break;
            case "home":
                // Kiosk: o botão Voltar nunca fecha o app nem revela o Android.
                showHome();
                break;
            default:
                showHome();
                break;
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("Sair do ISM Telecom?")
                .setMessage("A consulta atual será encerrada. Deseja fechar o aplicativo?")
                .setNegativeButton("Cancelar", (d, w) -> {})
                .setPositiveButton("Sair", (d, w) -> {
                    resetSession();
                    closePdf();
                    finishAffinity();
                })
                .show();
    }

    private boolean terminalConfigured() {
        return TerminalKeyStore.read(this).length() >= 32;
    }

    private String terminalKey() {
        return TerminalKeyStore.read(this);
    }

    private String apiBase() {
        return BuildConfig.API_BASE.startsWith("https://")
                ? BuildConfig.API_BASE : "https://app.ismtelecom.com.br";
    }

    /**
     * Ativação pela câmera, sem mostrar a chave permanente em um QR.
     * O servidor pode emitir um QR compartilhado que serve para todas as maquinetas.
     */
    private void showTerminalSetup() {
        if (terminalConfigured()) {
            showHome();
            return;
        }
        resetSession();
        closePdf();
        screenType = "setup";
        initScreen("Ativar terminal ISM",
                "Escaneie o QR Code enviado pelo administrador para ativar automaticamente.",
                false);
        content.addView(label("Servidor: " + apiBase(), 13, NAVY, true),
                margins(-1, -2, 16));

        Button scan = button("Escanear QR Code de ativação", CYAN, true);
        scan.setOnClickListener(v -> scanActivationQRCode());
        content.addView(scan, margins(-1, dp(72), 14));
        content.addView(label(
                "O mesmo QR Code pode ativar todas as maquinetas ISM. "
                + "Se o administrador o revogar, solicite a imagem atualizada.",
                13, MUTED, false), margins(-1, -2, 18));

        Button manual = button("Ativar digitando a chave (alternativa)", NAVY, false);
        manual.setOnClickListener(v -> {
            manual.setEnabled(false);
            showManualActivationFields();
        });
        content.addView(manual, margins(-1, dp(52), 10));
    }

    private void showManualActivationFields() {
        content.addView(label("Digite a chave apenas se não puder escanear o QR Code.",
                13, MUTED, false), margins(-1, -2, 12));
        EditText credential = field("Chave de ativação do terminal",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        credential.setSelectAllOnFocus(true);
        Button activate = button("Ativar maquininha com chave", NAVY, true);
        activate.setOnClickListener(v -> {
            if (loading || terminalConfigured()) return;
            String candidate = credential.getText().toString().trim();
            if (candidate.length() < 32 || candidate.length() > 512) {
                dialog("A chave de ativação deve ter entre 32 e 512 caracteres.");
                return;
            }
            hideKeyboard(credential);
            validateAndSaveTerminalKey(candidate, activate, credential);
        });
        content.addView(activate, margins(-1, dp(54), 8));
    }

    private void scanActivationQRCode() {
        if (loading || terminalConfigured()) return;
        if (!getPackageManager().hasSystemFeature("android.hardware.camera.any")) {
            dialog("Esta maquineta não possui câmera disponível. Utilize a chave manual.");
            return;
        }
        try {
            new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
                    .setPrompt("Aponte a câmera para o QR Code de ativação ISM")
                    .initiateScan();
        } catch (Exception error) {
            dialog("Não foi possível abrir a câmera. Utilize a chave manual.");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult scanned = IntentIntegrator.parseActivityResult(
                requestCode, resultCode, data);
        if (scanned == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (terminalConfigured() || !"setup".equals(screenType)) return;
        String payload = scanned.getContents();
        if (payload == null) return; // Digitalização cancelada.
        final String prefix = "ISM-ACT1:";
        if (!payload.startsWith(prefix)
                || !payload.substring(prefix.length()).matches("[A-Za-z0-9_-]{43}")) {
            dialog("QR Code inválido. Peça a imagem gerada pelo servidor ISM.");
            return;
        }
        activateFromQr(payload.substring(prefix.length()));
    }

    private void activateFromQr(String code) {
        if (loading || terminalConfigured()) return;
        final String installation;
        try {
            installation = TerminalRegistryStore.installationId(this);
        } catch (IOException error) {
            dialog("Não foi possível gerar a identificação deste terminal.");
            return;
        }
        loading = true;
        final int epoch = requestEpoch;
        ProgressBar progress = new ProgressBar(this);
        content.addView(progress, margins(-1, dp(45), 10));
        executor.execute(() -> {
            HttpURLConnection conn = null;
            String key = "";
            String terminalNumber = "";
            String error = "Não foi possível ativar. Verifique a conexão da maquineta.";
            try {
                conn = (HttpURLConnection) new URL(
                        apiBase().replaceAll("/+$", "") + "/api/v1/terminal/ativar-qr"
                ).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Cache-Control", "no-store");
                JSONObject data = new JSONObject();
                data.put("codigo", code);
                data.put("installation_uuid", installation);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = conn.getResponseCode();
                if (status == 200) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (InputStream input = conn.getInputStream()) {
                        byte[] bytes = new byte[1024];
                        int read;
                        while ((read = input.read(bytes)) != -1) {
                            buffer.write(bytes, 0, read);
                            if (buffer.size() > 4096)
                                throw new IOException("Resposta de ativação muito grande");
                        }
                    }
                    JSONObject json = new JSONObject(buffer.toString("UTF-8"));
                    if (json.optBoolean("autorizado", false)) {
                        String secret = json.optString("terminal_key", "");
                        if (secret.length() >= 32 && secret.length() <= 512) {
                            key = secret;
                            terminalNumber = json.optString("terminal_id", "");
                            if (!terminalNumber.matches("ISM-[0-9]{4,12}")) {
                                key = "";
                                error = "Atualize o backend ISM para v0.12.1 antes de ativar.";
                            }
                        }
                    }
                } else if (status == 401 || status == 422) {
                    error = "QR Code inválido, revogado ou expirado. Solicite ao administrador.";
                } else if (status == 404) {
                    error = "Atualize o backend ISM para v0.11.6 antes de ativar por QR.";
                } else {
                    error = "Servidor não autorizou a ativação (HTTP " + status + ").";
                }
            } catch (Exception ex) {
                error = "Falha de conexão. Confirme a internet e o servidor ISM.";
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String receivedKey = key;
            final String assignedNumber = terminalNumber;
            final String failure = error;
            ui.post(() -> {
                if (epoch != requestEpoch) return;
                loading = false;
                removeProgress(progress);
                if (receivedKey.isEmpty()) {
                    dialog(failure);
                    return;
                }
                try {
                    TerminalRegistryStore.saveNumberOnce(this, assignedNumber);
                    TerminalKeyStore.save(this, receivedKey);
                    if (!terminalConfigured())
                        throw new IOException("Não foi possível conferir chave protegida");
                    showHome();
                    dialog("Maquineta ISM ativada automaticamente pelo QR Code!");
                } catch (IOException ex) {
                    dialog("Não foi possível guardar a ativação protegida neste aparelho.");
                }
            });
        });
    }

    private void validateAndSaveTerminalKey(String candidate, Button activate, EditText entry) {
        if (loading) return;
        loading = true;
        activate.setEnabled(false);
        final int epoch = requestEpoch;
        final String url = apiBase();
        ProgressBar progress = new ProgressBar(this);
        content.addView(progress, margins(-1, dp(38), 10));
        executor.execute(() -> {
            HttpURLConnection connection = null;
            boolean authorized = false;
            String failure = "Não foi possível validar a chave de terminal. Confira a rede e a API ISM.";
            try {
                connection = (HttpURLConnection) new URL(
                        url.replaceAll("/+$", "") + "/api/v1/terminal/validar").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("X-Terminal-Key", candidate);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write("{}".getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                if (status == 200) {
                    try (InputStream input = connection.getInputStream()) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            bytes.write(buffer, 0, n);
                            if (bytes.size() > 4096) throw new IOException("Resposta muito grande");
                        }
                        authorized = new JSONObject(bytes.toString("UTF-8"))
                                .optBoolean("autorizado", false);
                    }
                } else if (status == 401) {
                    failure = "Chave recusada pelo servidor. Confira a TERMINAL_KEY.";
                } else if (status == 404) {
                    failure = "A versão do servidor é antiga. Atualize o backend ISM antes de ativar.";
                } else {
                    failure = "Servidor não autorizou o terminal (HTTP " + status + ").";
                }
            } catch (Exception error) {
                failure = "Falha de conexão. Verifique o domínio, internet e servidor ISM.";
            } finally {
                if (connection != null) connection.disconnect();
            }

            final boolean accepted = authorized;
            final String errorText = failure;
            ui.post(() -> {
                if (epoch != requestEpoch) return;
                loading = false;
                removeProgress(progress);
                activate.setEnabled(true);
                if (!accepted) {
                    dialog(errorText);
                    return;
                }
                try {
                    TerminalKeyStore.save(this, candidate);
                    if (!terminalConfigured()) throw new IOException("Falha ao ler chave cifrada");
                    entry.setText("");
                    showHome();
                    dialog("Terminal ISM ativado. A configuração está bloqueada neste aparelho.");
                } catch (IOException error) {
                    dialog("Não foi possível guardar a chave de forma protegida no Android.");
                }
            });
        });
    }

    private EditText field(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(16);
        edit.setHint(hint);
        edit.setInputType(inputType);
        edit.setPadding(dp(14), dp(10), dp(14), dp(10));
        edit.setBackground(shape(Color.WHITE, 10, Color.rgb(208, 225, 235)));
        content.addView(edit, margins(-1, dp(52), 14));
        return edit;
    }

    private String digits(String s) { return s.replaceAll("[^0-9]", ""); }

    private String money(String raw) {
        try {
            return "R$ " + String.format(new Locale("pt", "BR"), "%,.2f", new BigDecimal(raw));
        } catch (Exception ex) {
            return "R$ " + raw;
        }
    }

    private String dateBR(String date) {
        try {
            return LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale("pt", "BR")));
        } catch (Exception ex) {
            return date;
        }
    }

    private boolean isLate(String date) {
        try { return LocalDate.parse(date).isBefore(LocalDate.now()); }
        catch (Exception ex) { return false; }
    }

    private boolean isSessionValid() {
        return !session.isEmpty() && deadline > SystemClock.elapsedRealtime();
    }

    private void startSession() {
        ui.removeCallbacks(sessionTick);
        deadline = SystemClock.elapsedRealtime() + SESSION_MILLIS;
        ui.post(sessionTick);
    }

    private void resetSession() {
        stopPixAutoCheck();
        ui.removeCallbacks(sessionTick);
        deadline = 0;
        session = "";
        invoiceResponse = null;
        filterMode = 0;
        currentPaymentInvoiceId = "";
        requestEpoch++;
    }

    private void showHome() {
        if (!terminalConfigured()) {
            showTerminalSetup();
            return;
        }
        screenType = "home";
        resetSession();
        closePdf();
        initScreen("", "", false);
        // Sem saudação: espaço visual dedicado à logomarca ISM.
        Button contract = button("Quero contratar", NAVY, false);
        contract.setOnClickListener(v -> showLead());
        content.addView(contract, margins(-1, dp(86), 14));

        Button bills = button("Boletos e pagamentos", CYAN, false);
        bills.setOnClickListener(v -> showLookup());
        content.addView(bills, margins(-1, dp(86), 22));

        TextView warning = label("Serviço de consulta. Confirme os dados antes de pagar no seu banco.", 12, MUTED, false);
        warning.setGravity(Gravity.CENTER);
        content.addView(warning, margins(-1, -2, 20));

        TextView provisioned = label("Terminal autorizado para consultas ISM", 11, MUTED, false);
        provisioned.setGravity(Gravity.CENTER);
        content.addView(provisioned, margins(-1, -2, 8));

        LinearLayout deviceInfo = col();
        deviceInfo.setPadding(dp(12), dp(10), dp(12), dp(10));
        deviceInfo.setBackground(shape(Color.WHITE, 10, Color.rgb(218, 231, 239)));
        String savedNumber = TerminalRegistryStore.assignedNumber(this);
        TextView registryNumber = label(savedNumber.isEmpty()
                ? "Terminal ISM: cadastro pendente"
                : "Terminal ISM: " + savedNumber, 13, NAVY, true);
        deviceInfo.addView(registryNumber, margins(-1, -2, 8));

        Button copyIdentity = button("Copiar identificação do terminal", NAVY, false);
        copyIdentity.setTextSize(11);
        copyIdentity.setEnabled(!savedNumber.isEmpty());
        copyIdentity.setOnClickListener(v -> {
            String assigned = TerminalRegistryStore.assignedNumber(this);
            if (assigned.isEmpty()) return;
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        ClipData.newPlainText("Identificação ISM", assigned));
                Toast.makeText(this, "Número do terminal copiado",
                        Toast.LENGTH_SHORT).show();
            }
        });
        deviceInfo.addView(copyIdentity, margins(-1, dp(43), 0));
        content.addView(deviceInfo, margins(-1, -2, 12));

        if (savedNumber.isEmpty()) {
            // Migração automática dos dispositivos ativados com o QR antigo:
            // não exige apagar a chave nem escanear outra vez o QR compartilhado.
            loadRegisteredTerminalNumber(registryNumber, copyIdentity);
        }
    }

    private void loadRegisteredTerminalNumber(TextView display, Button copyButton) {
        final int epoch = requestEpoch;
        final String secret = terminalKey();
        if (secret.length() < 32) return;
        registryExecutor.execute(() -> {
            HttpURLConnection connection = null;
            String number = "";
            try {
                String installation = TerminalRegistryStore.installationId(
                        getApplicationContext());
                connection = (HttpURLConnection) new URL(
                        apiBase().replaceAll("/+$", "")
                        + "/api/v1/terminal/identificar").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(8000);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("X-Terminal-Key", secret);
                JSONObject data = new JSONObject();
                data.put("installation_uuid", installation);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (connection.getResponseCode() == 200) {
                    try (InputStream input = connection.getInputStream()) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            bytes.write(buffer, 0, n);
                            if (bytes.size() > 4096) throw new IOException(
                                    "Cadastro de terminal excedeu tamanho");
                        }
                        number = new JSONObject(bytes.toString("UTF-8"))
                                .optString("terminal_id", "");
                    }
                    TerminalRegistryStore.saveNumberOnce(
                            getApplicationContext(), number);
                }
            } catch (Exception ignored) {
                // Sem rede, mantém número já salvo ou mostra cadastro pendente.
                // Não interromper a consulta de CPF nem o atendimento.
            } finally {
                if (connection != null) connection.disconnect();
            }
            String numberSaved = TerminalRegistryStore.assignedNumber(this);
            ui.post(() -> {
                if (epoch != requestEpoch || !"home".equals(screenType)) return;
                if (!numberSaved.isEmpty()) {
                    display.setText("Terminal ISM: " + numberSaved);
                    copyButton.setEnabled(true);
                } else {
                    display.setText("Terminal ISM: cadastro pendente");
                }
            });
        });
    }

    private void showLookup() {
        screenType = "lookup";
        resetSession();
        closePdf();
        initScreen("Boletos e\npagamentos", "Insira seu CPF ou CNPJ para consultar as faturas.", true);
        EditText document = field("CPF ou CNPJ", InputType.TYPE_CLASS_NUMBER);
        primary("Avançar", v -> {
            String number = digits(document.getText().toString());
            if (number.length() != 11 && number.length() != 14) {
                dialog("Informe um CPF de 11 dígitos ou CNPJ de 14 dígitos."); return;
            }
            hideKeyboard(document);
            JSONObject body = new JSONObject();
            try { body.put("cpf", number); } catch (JSONException ignored) {}
            call("/api/v1/faturas", body, result -> {
                invoiceResponse = result;
                session = result.optString("session");
                if (!session.isEmpty()) startSession();
                renderInvoices();
            });
        });
    }

    private void showCustomerInfo() {
        if (!isSessionValid() || invoiceResponse == null) return;
        JSONObject customer = invoiceResponse.optJSONObject("cliente");
        if (customer == null) return;
        String name = customer.optString("nome", "").trim();
        String document = customer.optString("cpf", "").trim();
        if (name.isEmpty() && document.isEmpty()) return;

        LinearLayout details = col();
        details.setPadding(dp(14), dp(12), dp(14), dp(12));
        details.setBackground(shape(Color.WHITE, 12, Color.rgb(222, 233, 241)));
        if (!name.isEmpty()) {
            details.addView(label("Cliente: " + name, 14, NAVY, true),
                    margins(-1, -2, 6));
        }
        if (!document.isEmpty()) {
            details.addView(label("CPF/CNPJ: " + document, 13, NAVY, false));
        }
        content.addView(details, margins(-1, -2, 14));
    }

    private void renderInvoices() {
        stopPixAutoCheck();
        screenType = "invoices";
        closePdf();
        if (invoiceResponse == null) { showHome(); return; }
        JSONArray invoices = invoiceResponse.optJSONArray("faturas");
        int count = invoices == null ? 0 : invoices.length();
        initScreen("Boletos e\npagamentos",
                count + (count == 1 ? " fatura disponível." : " faturas disponíveis."), true);
        showCustomerInfo();

        JSONObject wa = invoiceResponse.optJSONObject("whatsapp");
        if (isSessionValid() && wa != null && wa.optBoolean("boas_vindas_disponivel", false)) {
            primary("Enviar boas-vindas ao novo cliente (WhatsApp)", v -> sendWelcomeMeta());
            content.addView(label("Somente cadastro recente no IXC, com autorização do cliente.",
                    12, MUTED, false), margins(-1, -2, 14));
        }

        if (isSessionValid()) {
            primary("Consultar PIX já recebidos", v -> showPaidPixHistory());
            content.addView(label("Consulte um PIX já pago no IXC e compartilhe a confirmação.",
                    12, MUTED, false), margins(-1, -2, 14));
        }

        if (count == 0) {
            TextView empty = label(invoiceResponse.optString("mensagem", "Não há cobranças disponíveis."),
                    16, NAVY, true);
            content.addView(empty, margins(-1, -2, 16));
            primary("Nova consulta", v -> showLookup());
            return;
        }

        Button filter = button("Filtrar faturas  ▾", CYAN, true);
        filter.setTextSize(13);
        filter.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Exibir faturas")
                .setItems(new String[]{"Todas", "Em aberto", "Vencidas"},
                        (d, selected) -> { filterMode = selected; renderInvoices(); })
                .show());
        LinearLayout filterContainer = new LinearLayout(this);
        filterContainer.setGravity(Gravity.RIGHT);
        filterContainer.addView(filter, new LinearLayout.LayoutParams(dp(160), dp(43)));
        content.addView(filterContainer, margins(-1, -2, 12));

        int visible = 0;
        for (int i = 0; i < count; i++) {
            JSONObject invoice = invoices.optJSONObject(i);
            if (invoice == null) continue;
            String due = invoice.optString("vencimento");
            boolean late = isLate(due);
            if ((filterMode == 1 && late) || (filterMode == 2 && !late)) continue;
            addInvoiceCard(invoice, late);
            visible++;
        }
        if (visible == 0) {
            TextView empty = label("Nenhuma fatura corresponde ao filtro selecionado.", 14, MUTED, false);
            content.addView(empty);
        }
    }

    /** Permite recuperar a confirmacao mesmo após o titulo sair de faturas em aberto.
     * Uma nova consulta por CPF/CNPJ gera uma nova sessão com tempo limitado. */
    private void showPaidPixHistory() {
        if (!isSessionValid()) {
            showLookup();
            dialog("Sessão expirada. Consulte o cliente novamente.");
            return;
        }
        // Renovação exige toque do operador e token anterior ainda válido.
        JSONObject request = new JSONObject();
        try { request.put("session", session); } catch (JSONException ignored) {}
        call("/api/v1/sessao/renovar", request, renewed -> {
            String fresh = renewed.optString("session");
            if (fresh.isEmpty()) {
                showLookup();
                dialog("Não foi possível renovar a sessão. Consulte o CPF novamente.");
                return;
            }
            session = fresh;
            startSession();
            renderPaidPixHistory();
        });
    }

    private void renderPaidPixHistory() {
        stopPixAutoCheck();
        if (!isSessionValid()) {
            showLookup();
            dialog("Sessão expirada. Consulte o cliente novamente.");
            return;
        }

        // Mostra a consulta pelo ID imediatamente, mesmo se a listagem falhar.
        screenType = "pix-history";
        closePdf();
        initScreen("PIX já recebidos",
                "Consulte uma fatura quitada por PIX sem pagar novamente.", true);
        showCustomerInfo();

        content.addView(label("Número da fatura PIX paga no IXC", 14, NAVY, true),
                margins(-1, -2, 8));
        EditText invoiceNumber = field("Número da fatura no IXC", InputType.TYPE_CLASS_NUMBER);
        primary("Verificar pelo número da fatura", v -> {
            String invoiceId = digits(invoiceNumber.getText().toString());
            if (invoiceId.isEmpty() || invoiceId.length() > 15) {
                dialog("Informe um número de fatura válido do IXC.");
                return;
            }
            hideKeyboard(invoiceNumber);
            checkPixPayment(invoiceId);
        });

        content.addView(label("Ou selecione um PIX recebido recentemente:", 14, NAVY, true),
                margins(-1, -2, 10));
        LinearLayout historyResults = col();
        content.addView(historyResults, margins(-1, -2, 15));
        historyResults.addView(label("Consultando recebimentos no IXC...", 12, MUTED, false));
        content.addView(label("A lista cobre até 100 títulos recentes. " +
                "Se não encontrar o pagamento, use o número da fatura acima.",
                12, MUTED, false), margins(-1, -2, 12));
        primary("Voltar às faturas", v -> {
            if (isSessionValid()) renderInvoices();
            else showLookup();
        });
        primary("Nova consulta", v -> showLookup());

        JSONObject request = new JSONObject();
        try { request.put("session", session); } catch (JSONException ignored) {}
        call("/api/v1/faturas/pix-recebidos", request, result -> {
            if (!"pix-history".equals(screenType)) return;
            historyResults.removeAllViews();
            JSONArray received = result.optJSONArray("pix_recebidos");
            int count = received == null ? 0 : received.length();
            if (count == 0) {
                historyResults.addView(label(
                        "Nenhum PIX confirmado encontrado entre os títulos recentes.",
                        12, MUTED, false));
                return;
            }
            for (int i = 0; i < count; i++) {
                JSONObject entry = received.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id");
                if (!id.matches("[0-9]+")) continue;
                LinearLayout card = col();
                card.setPadding(dp(12), dp(12), dp(12), dp(12));
                card.setBackground(shape(Color.WHITE, 14, Color.rgb(218, 233, 240)));
                card.addView(label("PIX recebido - Fatura " + id, 14, GREEN, true),
                        margins(-1, -2, 8));
                card.addView(label("Valor: " + money(entry.optString("valor_recebido")),
                        14, NAVY, true), margins(-1, -2, 8));
                String date = entry.optString("data_pagamento");
                if (!date.isEmpty()) card.addView(
                        label("Pagamento: " + dateBR(date), 12, MUTED, false),
                        margins(-1, -2, 8));
                Button confirm = button("Verificar e enviar confirmação", CYAN, true);
                confirm.setOnClickListener(v -> checkPixPayment(id));
                card.addView(confirm, margins(-1, dp(49), 0));
                historyResults.addView(card, margins(-1, -2, 10));
            }
        }, message -> {
            if (!"pix-history".equals(screenType)) return;
            historyResults.removeAllViews();
            historyResults.addView(label("Lista indisponível: " + message +
                            " Você ainda pode consultar pelo número da fatura acima.",
                    12, RED, false));
        });
    }

    private void addInvoiceCard(JSONObject invoice, boolean late) {
        final String id = invoice.optString("id");
        LinearLayout card = col();
        card.setBackground(shape(Color.WHITE, 16, Color.rgb(222, 233, 241)));
        card.setPadding(dp(0), 0, 0, dp(11));
        int accent = late ? RED : GREEN;
        TextView status = label(late ? "Vencido" : "Em aberto", 14, Color.WHITE, true);
        status.setGravity(Gravity.CENTER);
        status.setBackground(shape(accent, 14, 0));
        card.addView(status, new LinearLayout.LayoutParams(-1, dp(30)));

        TextView due = label("Vencimento: " + dateBR(invoice.optString("vencimento")), 13, NAVY, false);
        due.setPadding(dp(12), dp(12), dp(12), 0);
        card.addView(due);
        TextView price = label("Valor: " + money(invoice.optString("valor")), 15, NAVY, true);
        price.setPadding(dp(12), dp(4), dp(12), dp(10));
        card.addView(price);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(8), 0, dp(8), 0);
        addCardAction(actions, "Imprimir\ncódigo", () -> printBarcodeOnly(id));
        addCardAction(actions, "Enviar\nboleto", () -> emailBoleto(id));
        addCardAction(actions, "Pagar\nboleto", () -> showPaymentOptions(id));
        card.addView(actions);
        content.addView(card, margins(-1, -2, 14));
    }

    private void addCardAction(LinearLayout actions, String title, Runnable click) {
        Button b = button(title, NAVY, false);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(53), 1);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        actions.addView(b, p);
    }


    /** Opções claras em cards, sem oferecer cartão antes da homologação TEF. */
    private void showPaymentOptions(String id) {
        if (!isSessionValid()) {
            showHome(); dialog("Sessão expirada. Faça nova consulta."); return;
        }
        currentPaymentInvoiceId = id;
        screenType = "payment-options";
        stopPixAutoCheck();
        closePdf();
        initScreen("Escolha como pagar",
                "Selecione uma opção para a fatura IXC nº " + id, true);
        Button pix = button("▦  Pagar com PIX (QR Code)", CYAN, true);
        pix.setOnClickListener(v -> requestPayment(id, "pix"));
        content.addView(pix, margins(-1, dp(75), 15));
        // Código bancário continua acessível pelo botão Imprimir código,
        // e o PDF permanece disponível no fluxo de boleto/e-mail.
        // Exibir as modalidades para orientar o cliente, sem iniciar transação
        // nem chamar TEF/PPComp até homologação com o adquirente.
        TextView unavailable = label("Pagamento com cartão — ainda indisponível",
                13, MUTED, true);
        content.addView(unavailable, margins(-1, -2, 10));
        Button credit = button("Cartão de crédito (em breve)", NAVY, false);
        credit.setEnabled(false);
        credit.setContentDescription("Cartão de crédito: indisponível");
        content.addView(credit, margins(-1, dp(65), 10));
        Button debit = button("Cartão de débito (em breve)", NAVY, false);
        debit.setEnabled(false);
        debit.setContentDescription("Cartão de débito: indisponível");
        content.addView(debit, margins(-1, dp(65), 15));
    }

    /** Cliente escolhe e-mail do IXC ou um endereço alternativo validado por código. */
    private void emailBoleto(String id) {
        if (!isSessionValid()) {
            showHome();
            dialog("Sessão expirada. Faça nova consulta.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Enviar boleto")
                .setItems(new String[]{
                        "Enviar ao e-mail cadastrado no IXC",
                        "Enviar para outro e-mail"
                }, (d, index) -> {
                    if (index == 0) emailBoletoIXC(id);
                    else emailBoletoAlternativo(id);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** IXC escolhe o destinatário conforme cadastro, sem envio SMTP pelo POS. */
    private void emailBoletoIXC(String id) {
        JSONObject emailInfo = invoiceResponse == null ? null
                : invoiceResponse.optJSONObject("boleto_email");
        if (emailInfo == null || !emailInfo.optBoolean("disponivel", false)) {
            dialog("Envio pelo IXC indisponível. Verifique a versão do servidor.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Enviar boleto")
                .setMessage("Enviar a fatura " + id + " para o e-mail cadastrado no IXC?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Confirmar envio", (d, which) -> {
                    if (!isSessionValid()) {
                        showHome();
                        dialog("Sessão expirada. Faça nova consulta.");
                        return;
                    }
                    JSONObject request = new JSONObject();
                    try {
                        request.put("session", session);
                        request.put("confirmar_envio", true);
                    } catch (JSONException ex) {
                        dialog("Erro ao preparar envio.");
                        return;
                    }
                    call("/api/v1/faturas/" + id + "/enviar-email", request, result -> {
                        if (result.optBoolean("solicitacao_aceita", false)) {
                            dialog("Boleto encaminhado para envio.");
                        } else {
                            dialog("O IXC não confirmou o pedido de envio.");
                        }
                    });
                }).show();
    }

    /** Código SMTP confirma posse do endereço antes de enviar dados financeiros. */
    private void emailBoletoAlternativo(String id) {
        if (!isSessionValid()) {
            showHome();
            dialog("Sessão expirada. Faça nova consulta.");
            return;
        }
        LinearLayout form = col();
        form.setPadding(dp(18), dp(8), dp(18), dp(4));
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(16);
        address.setHint("Digite o novo e-mail");
        address.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(address, margins(-1, dp(54), 12));
        CheckBox consent = new CheckBox(this);
        consent.setText("Confirmo que o titular autorizou enviar o boleto a este e-mail.");
        consent.setTextSize(13);
        consent.setTextColor(NAVY);
        form.addView(consent, margins(-1, -2, 6));

        AlertDialog confirm = new AlertDialog.Builder(this)
                .setTitle("Enviar para outro e-mail")
                .setMessage("Enviaremos um código para confirmar o novo endereço antes do boleto.")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar código", null)
                .create();
        confirm.setOnShowListener(ignored -> confirm.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String email = address.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (email.length() > 254
                    || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                address.setError("Informe um e-mail válido");
                return;
            }
            if (!consent.isChecked()) {
                dialog("Confirme a autorização do titular.");
                return;
            }
            if (!isSessionValid()) {
                confirm.dismiss();
                dialog("Sessão expirada. Consulte o CPF novamente.");
                return;
            }
            JSONObject request = new JSONObject();
            try {
                request.put("session", session);
                request.put("email", email);
                request.put("consentimento", true);
            } catch (JSONException ex) {
                dialog("Não foi possível preparar o envio.");
                return;
            }
            confirm.dismiss();
            hideKeyboard(address);
            call("/api/v1/faturas/" + id + "/email-alternativo/solicitar-codigo",
                    request, result -> showAlternateEmailCodeDialog(id, email));
        }));
        confirm.show();
    }

    private void showAlternateEmailCodeDialog(String id, String email) {
        EditText code = new EditText(this);
        code.setSingleLine(true);
        code.setTextSize(18);
        code.setHint("Código de seis dígitos");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(6)});
        LinearLayout box = col();
        box.setPadding(dp(18), dp(6), dp(18), dp(4));
        box.addView(code, margins(-1, dp(54), 10));
        AlertDialog confirm = new AlertDialog.Builder(this)
                .setTitle("Confirmar novo e-mail")
                .setMessage("Digite o código enviado para " + email + ".")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar boleto", null)
                .create();
        confirm.setOnShowListener(ignored -> confirm.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String typed = code.getText().toString().trim();
            if (!typed.matches("[0-9]{6}")) {
                code.setError("Código de seis dígitos");
                return;
            }
            if (!isSessionValid()) {
                confirm.dismiss();
                dialog("Sessão expirada. Consulte o CPF novamente.");
                return;
            }
            JSONObject request = new JSONObject();
            try {
                request.put("session", session);
                request.put("email", email);
                request.put("codigo", typed);
                request.put("consentimento", true);
            } catch (JSONException ex) {
                dialog("Não foi possível preparar envio.");
                return;
            }
            confirm.dismiss();
            hideKeyboard(code);
            call("/api/v1/faturas/" + id + "/email-alternativo/confirmar-envio",
                    request, result -> {
                        if (result.optBoolean("solicitacao_aceita", false)) {
                            dialog("Boleto encaminhado para envio.");
                        } else {
                            dialog("Não foi possível confirmar o envio do boleto.");
                        }
                    });
        }));
        confirm.show();
    }

    /** Cupom estreito para a bobina térmica, sem abrir PDF ou Android PrintManager. */
    private void printBarcodeOnly(String id) {
        if (!isSessionValid()) {
            showHome();
            dialog("Sessão expirada. Consulte novamente.");
            return;
        }
        JSONObject request = new JSONObject();
        try { request.put("session", session); } catch (JSONException ignored) {}
        call("/api/v1/faturas/" + id + "/barra", request, result -> {
            String number;
            try {
                number = BrazilianBoletoBarcode.convert(result.optString("codigo", ""));
            } catch (IllegalArgumentException ex) {
                dialog("O IXC não forneceu um código bancário válido para esta cobrança.");
                return;
            }
            String issued = result.optString("emissao", "");
            String due = result.optString("vencimento", "");
            String value = result.optString("valor", "");

            Bitmap receipt;
            try {
                receipt = ThermalReceipt.render(
                        id,
                        issued.isEmpty() ? "" : dateBR(issued),
                        dateBR(due),
                        money(value),
                        number,
                        result.optString("beneficiario", "")
                );
            } catch (Exception ex) {
                dialog("Não foi possível preparar o recibo térmico. Verifique os dados do IXC.");
                return;
            }

            executor.execute(() -> {
                GertecNativePrinter.Result printed = GertecNativePrinter.print(getApplicationContext(), receipt);
                ui.post(() -> {
                    if (printed.submitted) {
                        dialog(printed.detail);
                    } else {
                        showThermalPreview(receipt, printed.detail);
                    }
                });
            });
        });
    }

    /** Em caso de SDK ausente mostra o layout real e o diagnóstico; não finge imprimir. */
    private void showThermalPreview(Bitmap receipt, String reason) {
        LinearLayout layout = col();
        layout.setPadding(dp(14), dp(9), dp(14), dp(9));
        TextView warning = label(reason, 13, RED, true);
        layout.addView(warning, margins(-1, -2, 12));
        ImageView image = new ImageView(this);
        image.setImageBitmap(receipt);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        layout.addView(image, margins(-1, dp(360), 4));
        new AlertDialog.Builder(this)
                .setTitle("Prévia de recibo térmico")
                .setView(layout)
                .setPositiveButton("Fechar", null)
                .show();
    }

    private void requestPayment(String id, String kind) {
        if (!isSessionValid()) { showHome(); dialog("Sessão expirada. Faça nova consulta."); return; }
        JSONObject request = new JSONObject();
        try { request.put("session", session); } catch (JSONException ignored) {}
        call("/api/v1/faturas/" + id + "/" + kind, request, this::showPayment);
    }

    private void showPayment(JSONObject result) {
        stopPixAutoCheck();
        screenType = "payment";
        closePdf();
        String kind = result.optString("tipo");
        initScreen("Dados para pagamento",
                money(result.optString("valor")) + "  •  Vencimento: " + dateBR(result.optString("vencimento")), true);
        String code = result.optString("codigo");
        if ("pix".equals(kind)) {
            content.addView(label("PIX copia e cola", 18, NAVY, true), margins(-1, -2, 12));
            displayQr(code);
            displayCode(code);
            primary("⇧  Compartilhar código PIX", v -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, code);
                    startActivity(Intent.createChooser(send, "Compartilhar PIX"));
                } catch (Exception ex) {
                    dialog("Nenhum aplicativo disponível para compartilhar o código PIX.");
                }
            });
            TextView help = label("O aplicativo verifica o PIX no IXC a cada 8 segundos, " +
                    "enquanto esta tela e a sessão estiverem ativas. " +
                    "O QR Code sozinho não comprova pagamento.", 12, MUTED, false);
            content.addView(help, margins(-1, -2, 12));
            String invoiceId = result.optString("id");
            pixWatchingInvoiceId = invoiceId;
            pixStatus = label("Aguardando a confirmação do PIX no IXC...", 14, NAVY, true);
            content.addView(pixStatus, margins(-1, -2, 12));
            primary("✓  Verificar pagamento PIX", v -> checkPixPayment(invoiceId, false));
            Button back = button("← Voltar às cobranças", NAVY, false);
            back.setOnClickListener(v -> renderInvoices());
            content.addView(back, margins(-1, dp(50), 12));
            ui.postDelayed(pixAutoCheck, 6000);
        } else if ("barra".equals(kind)) {
            content.addView(label("Linha digitável", 18, NAVY, true), margins(-1, -2, 12));
            displayCode(code);
        } else if ("boleto".equals(kind)) {
            String encoded = result.optString("pdf_base64");
            String link = result.optString("url");
            if (!encoded.isEmpty()) {
                try {
                    displayPdf(encoded);
                    primary("Enviar boleto por e-mail (IXC)",
                            v -> emailBoleto(String.valueOf(result.optString("id"))));
                    primary("Imprimir código na térmica",
                            v -> printBarcodeOnly(String.valueOf(result.optString("id"))));
                    primary("Compartilhar arquivo PDF", v -> sharePdf());
                } catch (Exception ex) { dialog("Não foi possível abrir o PDF recebido."); }
            } else if (link.startsWith("https://")) {
                primary("Abrir boleto", v -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
                    catch (Exception ex) { dialog("Nenhum visualizador compatível instalado."); }
                });
            } else {
                dialog("O IXC não disponibilizou este boleto.");
            }
        }
        TextView reminder = label(
                "Confira beneficiário, valor e vencimento no aplicativo do seu banco antes de pagar.",
                12, MUTED, false);
        content.addView(reminder, margins(-1, -2, 15));
    }

    private void checkPixPayment(String invoiceId) {
        checkPixPayment(invoiceId, false);
    }

    private void checkPixPayment(String invoiceId, boolean automatic) {
        if (!isSessionValid()) {
            showHome();
            dialog("Sessão expirada. Consulte o cliente novamente.");
            return;
        }
        if (!invoiceId.matches("[0-9]+")) {
            dialog("Cobrança inválida para confirmação.");
            return;
        }
        JSONObject request = new JSONObject();
        try { request.put("session", session); } catch (JSONException ignored) {}
        call("/api/v1/faturas/" + invoiceId + "/confirmar-pix", request, result -> {
            if (automatic && (!"payment".equals(screenType)
                    || !invoiceId.equals(pixWatchingInvoiceId))) return;
            if (result.optBoolean("confirmado", false)
                    && "confirmado".equals(result.optString("situacao"))) {
                // O operador tem 60 segundos novos para visualizar e compartilhar o PDF.
                stopPixAutoCheck();
                JSONObject renewal = new JSONObject();
                try { renewal.put("session", session); } catch (JSONException ignored) {}
                call("/api/v1/sessao/renovar", renewal, renewed -> {
                    String fresh = renewed.optString("session");
                    if (fresh.isEmpty()) {
                        showLookup();
                        dialog("Pagamento confirmado no IXC, mas a sessão expirou. Consulte novamente.");
                        return;
                    }
                    session = fresh;
                    startSession();
                    showPixReceipt(result);
                }, message -> {
                    showLookup();
                    dialog("PIX confirmado no IXC, mas não foi possível renovar a sessão. " +
                            "Consulte novamente para compartilhar a confirmação.");
                });
                return;
            }
            String state = result.optString("situacao");
            String detail;
            switch (state) {
                case "aguardando": detail = "O PIX ainda não foi confirmado no IXC. " +
                        "A atualização pode demorar conforme o banco. Verifique novamente."; break;
                case "outro_meio": detail = "A cobrança consta recebida, mas não foi " +
                        "identificada como PIX. Não será emitida confirmação PIX."; break;
                case "parcial": detail = "A cobrança possui saldo em aberto. " +
                        "Não é possível emitir confirmação de quitação."; break;
                case "estornado": detail = "O pagamento consta estornado. " +
                        "Não será gerado comprovante."; break;
                case "cancelado": detail = "A cobrança está cancelada no IXC."; break;
                default: detail = "Não foi possível comprovar o recebimento via PIX " +
                        "com os dados atuais do IXC. Consulte o financeiro."; break;
            }
            if (automatic) {
                if (pixStatus != null) pixStatus.setText(detail);
                if (!"aguardando".equals(state)) stopPixAutoCheck();
            } else {
                dialog(detail);
            }
        }, message -> {
            if (automatic) {
                if (pixStatus != null) pixStatus.setText(
                        "Falha na consulta. Use o botão Verificar pagamento agora.");
                stopPixAutoCheck();
            } else {
                dialog(message);
            }
        });
    }

    private void showPixReceipt(JSONObject receipt) {
        stopPixAutoCheck();
        screenType = "receipt";
        closePdf();
        initScreen("Pagamento confirmado", "A baixa via PIX foi confirmada no IXC.", true);
        showCustomerInfo();
        TextView success = label("✓ PIX RECEBIDO", 20, GREEN, true);
        success.setGravity(Gravity.CENTER);
        success.setPadding(dp(10), dp(20), dp(10), dp(20));
        success.setBackground(shape(Color.WHITE, 14, 0));
        content.addView(success, margins(-1, -2, 14));

        String amount = receipt.optString("valor_recebido");
        String paymentDate = receipt.optString("data_pagamento");
        String invoiceId = receipt.optString("id");
        content.addView(label("Recebido: " + money(amount), 17, NAVY, true),
                margins(-1, -2, 9));
        content.addView(label("Fatura IXC nº " + invoiceId, 14, NAVY, false),
                margins(-1, -2, 9));
        if (!paymentDate.isEmpty()) {
            content.addView(label("Pagamento: " + paymentDate, 14, NAVY, false),
                    margins(-1, -2, 9));
        }
        TextView note = label(
                "Confirmação informativa baseada no registro do IXC. " +
                "Não substitui o comprovante bancário do pagador.", 12, MUTED, false);
        content.addView(note, margins(-1, -2, 16));

        JSONObject wa = invoiceResponse == null ? null : invoiceResponse.optJSONObject("whatsapp");
        if (wa != null && wa.optBoolean("confirmacao_pix_disponivel", false)) {
            primary("Enviar confirmação PIX via API WhatsApp", v -> sendPixMeta(invoiceId));
        } else {
            content.addView(label(
                    "Envio pela API da Meta ainda não configurado para este cliente no servidor. " +
                    "A confirmação por PDF continua disponível.",
                    12, MUTED, false), margins(-1, -2, 12));
        }
        primary("Compartilhar PDF pelo Android", v -> {
            if (!isSessionValid()) {
                showHome();
                dialog("Sessão expirada. Consulte novamente para emitir confirmação.");
                return;
            }
            try {
                createReceiptPdf(receipt);
                sharePixConfirmation(receipt);
            } catch (Exception ex) {
                dialog("Não foi possível preparar a confirmação de recebimento.");
            }
        });
        primary("Nova consulta", v -> showLookup());
    }

    private String maskedMetaNumber() {
        if (invoiceResponse == null) return "";
        JSONObject wa = invoiceResponse.optJSONObject("whatsapp");
        return wa == null ? "" : wa.optString("destinatario_mascarado", "");
    }

    private void sendWelcomeMeta() {
        if (!isSessionValid()) {
            showLookup();
            dialog("Sessão expirada. Consulte o CPF novamente.");
            return;
        }
        String masked = maskedMetaNumber();
        new AlertDialog.Builder(this)
                .setTitle("Boas-vindas ao novo cliente")
                .setMessage("Confirma que o cadastro do assinante é novo no IXC e que ele " +
                        "autorizou receber uma mensagem no WhatsApp " + masked + "? " +
                        "O envio será realizado pelo servidor usando um modelo aprovado pela Meta.")
                .setNegativeButton("Cancelar", (d, which) -> {})
                .setPositiveButton("Cliente autorizou: enviar", (d, which) -> {
                    JSONObject request = new JSONObject();
                    try {
                        request.put("session", session);
                        request.put("consentimento", true);
                        request.put("confirma_novo_cliente", true);
                    } catch (JSONException ignored) {}
                    call("/api/v1/whatsapp/boas-vindas", request,
                            result -> showMetaSendStatus(result));
                }).show();
    }

    private void sendPixMeta(String invoiceId) {
        if (!isSessionValid()) {
            showLookup();
            dialog("Sessão expirada. Consulte o CPF novamente.");
            return;
        }
        String masked = maskedMetaNumber();
        new AlertDialog.Builder(this)
                .setTitle("Confirmar envio de recebimento PIX")
                .setMessage("O servidor consultará novamente o IXC para conferir a baixa " +
                        "desta fatura e enviará uma mensagem pelo WhatsApp " + masked + ". " +
                        "O cliente autorizou o recebimento?")
                .setNegativeButton("Cancelar", (d, which) -> {})
                .setPositiveButton("Cliente autorizou: enviar", (d, which) -> {
                    JSONObject request = new JSONObject();
                    try {
                        request.put("session", session);
                        request.put("consentimento", true);
                    } catch (JSONException ignored) {}
                    call("/api/v1/whatsapp/pix/" + invoiceId, request,
                            result -> showMetaSendStatus(result));
                }).show();
    }

    private void showMetaSendStatus(JSONObject result) {
        if (result.optBoolean("solicitado", false)) {
            String provider = "aceito_opa".equals(result.optString("situacao"))
                    ? "Opa! Suite" : "Meta";
            dialog(provider + " aceitou o pedido para o WhatsApp " +
                    result.optString("destinatario_mascarado") +
                    ". O envio não significa entrega; confirme o status na plataforma.");
        } else {
            dialog(result.optString("mensagem",
                    "Essa mensagem já foi solicitada anteriormente. " +
                    "Consulte o histórico no sistema de atendimento WhatsApp."));
        }
    }

    private void createReceiptPdf(JSONObject receipt) throws IOException {
        if (!receipt.optBoolean("confirmado") ||
                !"confirmado".equals(receipt.optString("situacao"))) {
            throw new IOException("Pagamento não confirmado");
        }
        closePdf();
        File directory = new File(getCacheDir(), "boletos");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Sem cache");
        tempPdf = new File(directory, "confirmacao_pix.pdf");

        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.PageInfo pageInfo =
                    new PdfDocument.PageInfo.Builder(595, 842, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(NAVY);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setTextSize(26);
            canvas.drawText("ISM Telecom", 42, 74, paint);
            paint.setTextSize(17);
            canvas.drawText("Confirmação de recebimento - PIX", 42, 120, paint);

            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(14);
            canvas.drawText("Status: CONFIRMADO NO IXC", 42, 166, paint);
            canvas.drawText("Fatura IXC: " + receipt.optString("id"), 42, 197, paint);
            canvas.drawText("Documento: " + receipt.optString("documento"), 42, 228, paint);
            canvas.drawText("Valor recebido: " +
                    money(receipt.optString("valor_recebido")), 42, 259, paint);
            String date = receipt.optString("data_pagamento");
            canvas.drawText("Data de pagamento: " +
                    (date.isEmpty() ? "Não informada pelo IXC" : date), 42, 290, paint);
            canvas.drawText("Verificado em: " +
                    receipt.optString("verificado_em"), 42, 321, paint);
            String txid = receipt.optString("pix_txid");
            if (!txid.isEmpty()) {
                paint.setTextSize(10);
                // Identificadores muito longos não devem exceder os limites da página.
                if (txid.length() > 66) txid = txid.substring(0, 66) + "...";
                canvas.drawText("TxID: " + txid, 42, 352, paint);
            }
            paint.setTextSize(13);
            paint.setColor(MUTED);
            canvas.drawText("Documento informativo emitido a partir da baixa", 42, 432, paint);
            canvas.drawText("registrada no IXC. Não substitui o comprovante", 42, 451, paint);
            canvas.drawText("original fornecido pelo banco do pagador.", 42, 470, paint);
            document.finishPage(page);
            try (FileOutputStream out = new FileOutputStream(tempPdf)) {
                document.writeTo(out);
            }
        } finally {
            document.close();
        }
    }

    private void sharePixConfirmation(JSONObject receipt) {
        if (tempPdf == null || !tempPdf.exists()) {
            dialog("Arquivo de confirmação indisponível.");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", tempPdf);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT,
                    "ISM Telecom: PIX confirmado no IXC para a fatura " +
                    receipt.optString("id") + ". Valor recebido: " +
                    money(receipt.optString("valor_recebido")) + ".");
            send.setClipData(ClipData.newUri(getContentResolver(), "Confirmação PIX", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // O técnico escolhe o contato. Não enviamos automaticamente a terceiros.
            Intent whatsapp = new Intent(send);
            whatsapp.setPackage("com.whatsapp");
            try {
                startActivity(whatsapp);
            } catch (ActivityNotFoundException normalNotInstalled) {
                Intent business = new Intent(send);
                business.setPackage("com.whatsapp.w4b");
                try {
                    startActivity(business);
                } catch (ActivityNotFoundException businessNotInstalled) {
                    startActivity(Intent.createChooser(send,
                            "WhatsApp ou outro aplicativo para enviar a confirmação"));
                }
            }
        } catch (Exception ex) {
            dialog("Não há aplicativo disponível para compartilhar a confirmação.");
        }
    }

    private void displayQr(String code) {
        if (code.isEmpty()) return;
        try {
            BitMatrix bits = new MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 280, 280);
            Bitmap image = Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < 280; x++) for (int y = 0; y < 280; y++)
                image.setPixel(x, y, bits.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(image);
            qr.setAdjustViewBounds(true);
            content.addView(qr, margins(-1, dp(258), 12));
        } catch (Exception ex) {
            dialog("Não foi possível apresentar o QR Code.");
        }
    }

    private void displayCode(String code) {
        TextView value = label(code, 15, NAVY, false);
        value.setTextIsSelectable(true);
        value.setPadding(dp(12), dp(14), dp(12), dp(14));
        value.setBackground(shape(Color.WHITE, 10, 0));
        content.addView(value, margins(-1, -2, 12));
        primary("Copiar código", v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Código de pagamento", code));
            dialog("Código copiado. Confira os dados no aplicativo do banco.");
        });
    }

    private void savePdf(String encoded) throws Exception {
        byte[] pdf = Base64.getDecoder().decode(encoded);
        if (pdf.length < 5 || pdf.length > 8 * 1024 * 1024
                || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D'
                || pdf[3] != 'F' || pdf[4] != '-') throw new IOException("PDF inválido");
        closePdf();
        File directory = new File(getCacheDir(), "boletos");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache indisponível");
        tempPdf = new File(directory, "boleto_temporario.pdf");
        try (FileOutputStream out = new FileOutputStream(tempPdf)) { out.write(pdf); }
    }

    private void displayPdf(String encoded) throws Exception {
        savePdf(encoded);
        pdfDescriptor = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(pdfDescriptor);
        currentPage = 0;
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        content.addView(image);
        TextView pageInfo = label("", 12, NAVY, true);
        content.addView(pageInfo);
        Runnable render = () -> {
            if (renderer == null) return;
            try (PdfRenderer.Page page = renderer.openPage(currentPage)) {
                int width = 620;
                int height = Math.max(1, (int) ((float) page.getHeight() / page.getWidth() * width));
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                image.setImageBitmap(bitmap);
                pageInfo.setText("Página " + (currentPage + 1) + " de " + renderer.getPageCount());
            } catch (Exception ex) { dialog("Falha ao visualizar o boleto."); }
        };
        render.run();
        Button next = button("Próxima página", NAVY, false);
        next.setOnClickListener(v -> {
            if (renderer != null) {
                currentPage = (currentPage + 1) % renderer.getPageCount();
                render.run();
            }
        });
        content.addView(next, margins(-1, dp(45), 12));
    }

    private void sharePdf() {
        if (tempPdf == null || !tempPdf.exists()) {
            dialog("Boleto indisponível. Abra novamente."); return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", tempPdf);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newUri(getContentResolver(), "Boleto", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Enviar boleto"));
        } catch (Exception ex) {
            dialog("Não existe aplicativo compatível para enviar o PDF.");
        }
    }

    private void printPdf() {
        if (tempPdf == null || !tempPdf.exists()) {
            dialog("Documento para impressão indisponível. Abra novamente."); return;
        }
        if (!getPackageManager().hasSystemFeature("android.software.print")) {
            dialog("O sistema da maquininha não disponibiliza impressão pelo Android. " +
                    "A impressora térmica Gertec precisa do SDK/serviço do fabricante.");
            return;
        }
        final File file = tempPdf;
        final boolean onlyBarcode = file.getName().contains("codigo_barras");
        try {
            PrintManager printer = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            if (printer == null) {
                dialog("Serviço de impressão Android indisponível neste terminal.");
                return;
            }
            printer.print(onlyBarcode ? "Código de barras ISM" : "Boleto ISM Telecom",
                    new PrintDocumentAdapter() {
                @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                        CancellationSignal cancellation, LayoutResultCallback callback, Bundle extras) {
                    if (cancellation.isCanceled()) { callback.onLayoutCancelled(); return; }
                    PrintDocumentInfo info = new PrintDocumentInfo.Builder("boleto.pdf")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                            .build();
                    callback.onLayoutFinished(info, true);
                }

                @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal cancellation, WriteResultCallback callback) {
                    try (InputStream from = new FileInputStream(file);
                         OutputStream to = new FileOutputStream(destination.getFileDescriptor())) {
                        byte[] block = new byte[8192];
                        int count;
                        while ((count = from.read(block)) != -1) {
                            if (cancellation.isCanceled()) {
                                callback.onWriteCancelled(); return;
                            }
                            to.write(block, 0, count);
                        }
                        to.flush();
                        callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                    } catch (Exception ex) { callback.onWriteFailed("Falha ao preparar impressão."); }
                }
            }, null);
        } catch (Exception ex) {
            dialog("A impressora interna Gertec não foi acessada pelo serviço Android. " +
                    "Será necessário integrar o SDK de impressão fornecido pela Gertec/Getnet.");
        }
    }

    private void showLead() {
        screenType = "lead";
        resetSession();
        closePdf();
        initScreen("Quero contratar", "Preencha os dados para nossa equipe comercial entrar em contato.", true);
        EditText name = field("Nome completo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText phone = field("Contato (DDD + telefone)", InputType.TYPE_CLASS_PHONE);
        EditText cpf = field("CPF do interessado", InputType.TYPE_CLASS_NUMBER);
        EditText customerId = field("ID Cliente (opcional)", InputType.TYPE_CLASS_TEXT);
        EditText tech = field("Identificação do técnico", InputType.TYPE_CLASS_TEXT);
        CheckBox consent = new CheckBox(this);
        consent.setText("O interessado autorizou a ISM Telecom a entrar em contato pelo WhatsApp ou telefone para tratar da contratação.");
        consent.setTextSize(13);
        consent.setTextColor(NAVY);
        content.addView(consent, margins(-1, -2, 12));

        primary("Enviar para o comercial", v -> {
            String nameValue = name.getText().toString().trim();
            String phoneValue = digits(phone.getText().toString());
            String cpfValue = digits(cpf.getText().toString());
            String techValue = tech.getText().toString().trim();
            if (nameValue.length() < 2 || cpfValue.length() != 11
                    || (phoneValue.length() != 10 && phoneValue.length() != 11)
                    || techValue.length() < 2 || !consent.isChecked()) {
                dialog("Preencha nome, CPF, telefone, identificação do técnico e consentimento.");
                return;
            }
            JSONObject data = new JSONObject();
            try {
                data.put("nome", nameValue);
                data.put("cpf", cpfValue);
                data.put("telefone", phoneValue);
                data.put("id_cliente", customerId.getText().toString().trim());
                data.put("tecnico", techValue);
                data.put("consentimento", true);
            } catch (JSONException ignored) {}
            hideKeyboard(tech);
            call("/api/v1/interessados", data, response -> {
                screenType = "lead-confirmation";
                boolean forwarded = response.optBoolean("enviado", false);
                String situation = response.optString("situacao", "pendente");
                if ("mensagem_cliente".equals(situation)) {
                    initScreen("Interesse registrado",
                            "Recebemos seu interesse! Solicitação de contato via WhatsApp aceita pelo Opa! Suite.",
                            true);
                } else if (forwarded) {
                    initScreen("Contato encaminhado",
                            "O canal comercial aceitou a solicitação. Aguarde o contato da equipe.",
                            true);
                } else if ("revisar".equals(situation)) {
                    initScreen("Contato registrado",
                            "Dados salvos com protocolo. O encaminhamento precisa ser verificado pelo administrador.",
                            true);
                } else {
                    initScreen("Contato registrado",
                            "Dados salvos no servidor. Encaminhamento ao comercial pendente.",
                            true);
                }
                content.addView(label("Protocolo: " + response.optString("protocolo"),
                        18, NAVY, true), margins(-1, -2, 14));
                content.addView(label(response.optString("mensagem",
                        "Solicitação registrada no servidor ISM."),
                        14, MUTED, false), margins(-1, -2, 16));
                primary("Voltar ao início", ignoredButton -> showHome());
            });
        });
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
        view.clearFocus();
    }

    private interface Success { void done(JSONObject response); }
    private interface Failure { void error(String message); }

    private void call(String path, JSONObject data, Success onSuccess) {
        call(path, data, onSuccess, null);
    }

    private void call(String path, JSONObject data, Success onSuccess, Failure onFailure) {
        if (loading) return;
        final String secretKey = terminalKey();
        final String baseUrl = apiBase();
        if (!baseUrl.startsWith("https://") || secretKey.length() < 32) {
            showTerminalSetup();
            dialog("Configure a chave do terminal para consultar o IXC.");
            return;
        }
        final int epoch = requestEpoch;
        loading = true;
        ProgressBar progress = new ProgressBar(this);
        content.addView(progress, margins(-1, dp(42), 10));
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(baseUrl.replaceAll("/+$", "") + path).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Terminal-Key", secretKey);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = conn.getResponseCode();
                InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                if (stream == null) throw new IOException("Sem resposta");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream input = stream) {
                    byte[] bytes = new byte[8192];
                    int read;
                    while ((read = input.read(bytes)) != -1) {
                        buffer.write(bytes, 0, read);
                        if (buffer.size() > 12 * 1024 * 1024) throw new IOException("Resposta muito grande");
                    }
                }
                JSONObject result = new JSONObject(buffer.toString("UTF-8"));
                if (status >= 400) {
                    if (status == 404 && "/api/v1/faturas/pix-recebidos".equals(path))
                        throw new IOException("Consultar PIX recebidos não está disponível no servidor. " +
                                "Atualize o backend ISM para v0.6 e tente novamente.");
                    throw new IOException(result.optString("detail", "Consulta indisponível"));
                }
                ui.post(() -> {
                    loading = false;
                    removeProgress(progress);
                    if (epoch != requestEpoch) return;
                    if (path.contains("/api/v1/faturas/") && !isSessionValid()) {
                        showHome();
                        dialog("Sessão expirada. Faça nova consulta.");
                        return;
                    }
                    onSuccess.done(result);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "Falha na comunicação" : error.getMessage();
                ui.post(() -> {
                    loading = false;
                    removeProgress(progress);
                    if (epoch == requestEpoch) {
                        if (onFailure != null) onFailure.error(message);
                        else dialog(message);
                    }
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void removeProgress(ProgressBar progress) {
        if (progress.getParent() instanceof ViewGroup) {
            ((ViewGroup) progress.getParent()).removeView(progress);
        }
    }

    private void dialog(String text) {
        new AlertDialog.Builder(this).setTitle("ISM Telecom").setMessage(text)
                .setPositiveButton("OK", (dialog, which) -> {}).show();
    }

    private void closePdf() {
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        renderer = null;
        try { if (pdfDescriptor != null) pdfDescriptor.close(); } catch (Exception ignored) {}
        pdfDescriptor = null;
        if (tempPdf != null) { tempPdf.delete(); tempPdf = null; }
    }

    @Override public void onBackPressed() { backToInvoicesOrHome(); }

    @Override protected void onDestroy() {
        stopPixAutoCheck();
        resetSession();
        closePdf();
        executor.shutdownNow();
        registryExecutor.shutdownNow();
        super.onDestroy();
    }
}
