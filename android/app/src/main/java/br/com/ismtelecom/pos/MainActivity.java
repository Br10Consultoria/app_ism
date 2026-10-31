package br.com.ismtelecom.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.graphics.pdf.PdfRenderer;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(8, 58, 112);
    private static final int ORANGE = Color.rgb(245, 130, 32);
    private static final int TEAL = Color.rgb(15, 159, 176);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private String mode = "", session = "";
    private File tempPdf;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pdfDescriptor;
    private int currentPage = 0;
    private boolean loading = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        showHome();
    }

    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private void initScreen(String title, String subtitle, boolean back) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247,249,252));
        content = column();
        content.setPadding(dp(20), dp(16), dp(20), dp(26));
        scroll.addView(content);
        setContentView(scroll);

        LinearLayout top = column();
        top.setPadding(dp(14), dp(14), dp(14), dp(16));
        top.setBackground(background(NAVY, 18));
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));
        TextView brand = label("ISM Telecom", 27, Color.WHITE, true);
        brand.setGravity(Gravity.CENTER);
        top.addView(brand);
        TextView fiber = label("100% Fibra Óptica", 12, Color.rgb(115,227,229), false);
        fiber.setGravity(Gravity.CENTER);
        top.addView(fiber);
        if (back) {
            Button goHome = button("‹  Voltar ao início", NAVY);
            goHome.setOnClickListener(v -> showHome());
            content.addView(goHome);
        }
        TextView head = label(title, 22, NAVY, true);
        head.setPadding(0, dp(18), 0, dp(4));
        content.addView(head);
        if (!subtitle.isEmpty()) {
            TextView desc = label(subtitle, 14, Color.DKGRAY, false);
            desc.setPadding(0, 0, 0, dp(16));
            content.addView(desc);
        }
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String text, int bg) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(background(bg, 13));
        b.setMinHeight(dp(62));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(62));
        lp.bottomMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText field(String hint, boolean number) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(17);
        edit.setHint(hint);
        edit.setPadding(dp(13), dp(12), dp(13), dp(12));
        edit.setBackground(background(Color.WHITE, 10));
        edit.setInputType(number ? InputType.TYPE_CLASS_PHONE : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58));
        lp.bottomMargin = dp(12);
        content.addView(edit, lp);
        return edit;
    }

    private String digits(String value) { return value.replaceAll("[^0-9]", ""); }

    private String money(String raw) {
        try { return "R$ " + String.format(new Locale("pt", "BR"), "%,.2f", new BigDecimal(raw)); }
        catch (Exception err) { return "R$ " + raw; }
    }

    private void showHome() {
        closePdf();
        mode = "";
        session = "";
        initScreen("Como podemos ajudar?", "Selecione uma das opções abaixo.", false);
        Button pix = button("◆  Pagar com PIX", TEAL);
        pix.setOnClickListener(v -> showLookup("pix"));
        content.addView(pix);
        Button bar = button("▥  Código de barras", NAVY);
        bar.setOnClickListener(v -> showLookup("barra"));
        content.addView(bar);
        Button boleto = button("▤  Segunda via de boleto", ORANGE);
        boleto.setOnClickListener(v -> showLookup("boleto"));
        content.addView(boleto);
        TextView line = label("ATENDIMENTO COMERCIAL", 12, NAVY, true);
        line.setPadding(0, dp(14), 0, dp(12));
        content.addView(line);
        Button lead = button("＋  Indicar novo cliente", NAVY);
        lead.setOnClickListener(v -> showLead());
        content.addView(lead);
        TextView help = label("Serviço de consulta e orientação. O pagamento é realizado no aplicativo do banco.", 12, Color.GRAY, false);
        help.setPadding(0, dp(15), 0, 0);
        content.addView(help);
    }

    private void showLookup(String selected) {
        closePdf();
        mode = selected;
        session = "";
        String t = mode.equals("pix") ? "PIX" : mode.equals("barra") ? "Código de barras" : "Segunda via do boleto";
        initScreen(t, "Informe o CPF do assinante para consultar faturas em aberto.", true);
        EditText cpf = field("CPF do assinante", true);
        Button search = button("Consultar faturas", TEAL);
        search.setOnClickListener(v -> {
            String number = digits(cpf.getText().toString());
            if (number.length() != 11) { dialog("Digite um CPF com 11 números."); return; }
            JSONObject data = new JSONObject();
            try { data.put("cpf", number); } catch (JSONException ignored) {}
            call("/api/v1/faturas", data, result -> showInvoices(result));
        });
        content.addView(search);
    }

    private void showInvoices(JSONObject result) {
        session = result.optString("session", "");
        JSONArray invoices = result.optJSONArray("faturas");
        initScreen("Faturas em aberto", "Selecione a cobrança que deseja consultar.", true);
        if (invoices == null || invoices.length() == 0) {
            content.addView(label(result.optString("mensagem", "Nenhuma cobrança disponível."), 17, NAVY, true));
            return;
        }
        for (int i = 0; i < invoices.length(); i++) {
            JSONObject invoice = invoices.optJSONObject(i);
            if (invoice == null) continue;
            final String invoiceId = invoice.optString("id");
            String text = money(invoice.optString("valor")) + "\nVencimento: " +
                          invoice.optString("vencimento") + "   •   Nº " + invoice.optString("documento", invoiceId);
            Button b = button(text, NAVY);
            b.setTextSize(14);
            b.setMinHeight(dp(78));
            b.setHeight(dp(78));
            b.setOnClickListener(v -> {
                JSONObject req = new JSONObject();
                try { req.put("session", session); } catch (JSONException ignored) {}
                call("/api/v1/faturas/" + invoiceId + "/" + mode, req, data -> showPayment(data));
            });
            content.addView(b);
        }
    }

    private void showPayment(JSONObject data) {
        initScreen("Dados para pagamento", money(data.optString("valor")) +
                "  •  Vencimento: " + data.optString("vencimento"), true);
        String kind = data.optString("tipo");
        String code = data.optString("codigo");
        if ("pix".equals(kind)) {
            content.addView(label("PIX copia e cola", 18, NAVY, true));
            displayQr(code);
            displayCode(code);
        } else if ("barra".equals(kind)) {
            content.addView(label("Linha digitável", 18, NAVY, true));
            displayCode(code);
        } else if ("boleto".equals(kind)) {
            String encoded = data.optString("pdf_base64");
            String link = data.optString("url");
            if (!encoded.isEmpty()) {
                try { displayPdf(encoded); }
                catch (Exception e) { dialog("Não foi possível abrir o PDF recebido."); }
            } else if (link.startsWith("https://")) {
                Button open = button("Abrir boleto PDF", TEAL);
                open.setOnClickListener(v -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
                    catch (Exception error) { dialog("Instale um visualizador de PDF compatível."); }
                });
                content.addView(open);
            } else {
                dialog("Boleto não disponibilizado pela carteira IXC.");
            }
        }
        TextView safe = label("Confira beneficiário, valor e vencimento no aplicativo do seu banco antes de pagar.", 13, Color.DKGRAY, false);
        safe.setPadding(0, dp(10), 0, 0);
        content.addView(safe);
    }

    private void displayQr(String code) {
        if (code.isEmpty()) return;
        try {
            BitMatrix bits = new MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 260, 260);
            Bitmap image = Bitmap.createBitmap(260, 260, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < 260; x++)
                for (int y = 0; y < 260; y++)
                    image.setPixel(x, y, bits.get(x,y) ? Color.BLACK : Color.WHITE);
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(image);
            qr.setAdjustViewBounds(true);
            content.addView(qr, new LinearLayout.LayoutParams(-1, dp(265)));
        } catch (Exception e) { dialog("QR Code indisponível."); }
    }

    private void displayCode(String code) {
        TextView text = label(code, 15, NAVY, false);
        text.setTextIsSelectable(true);
        text.setPadding(dp(10), dp(18), dp(10), dp(18));
        text.setBackground(background(Color.WHITE, 10));
        content.addView(text);
        Button copy = button("Copiar código", TEAL);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Código para pagamento", code));
            dialog("Código copiado. Confirme os dados no aplicativo do banco.");
        });
        content.addView(copy);
    }

    private void displayPdf(String encoded) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length > 8 * 1024 * 1024 || bytes.length < 5) throw new IOException();
        closePdf();
        tempPdf = new File(getCacheDir(), "boleto_temporario.pdf");
        try (FileOutputStream out = new FileOutputStream(tempPdf)) { out.write(bytes); }
        pdfDescriptor = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(pdfDescriptor);
        currentPage = 0;
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        content.addView(image);
        TextView pageInfo = label("", 13, NAVY, true);
        content.addView(pageInfo);
        Runnable render = () -> {
            if (renderer == null) return;
            try (PdfRenderer.Page page = renderer.openPage(currentPage)) {
                int w = 640, h = Math.max(1, (int)((float)page.getHeight()/page.getWidth()*w));
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                image.setImageBitmap(bitmap);
                pageInfo.setText("Página " + (currentPage + 1) + " de " + renderer.getPageCount());
            } catch (Exception e) { dialog("Falha ao visualizar o boleto."); }
        };
        render.run();
        Button next = button("Próxima página", NAVY);
        next.setOnClickListener(v -> {
            if (renderer != null) { currentPage = (currentPage + 1) % renderer.getPageCount(); render.run(); }
        });
        content.addView(next);
    }

    private void showLead() {
        closePdf();
        session = "";
        initScreen("Indicar novo cliente", "Registre o interessado para a equipe comercial da ISM Telecom entrar em contato.", true);
        EditText cpf = field("CPF do interessado", true);
        EditText phone = field("Telefone com DDD", true);
        EditText name = field("Nome (opcional)", false);
        EditText tech = field("Nome ou matrícula do técnico", false);
        CheckBox consent = new CheckBox(this);
        consent.setText("O interessado autorizou a ISM a entrar em contato sobre serviços de internet.");
        consent.setTextSize(13);
        content.addView(consent);
        Button send = button("Enviar para o comercial", TEAL);
        send.setOnClickListener(v -> {
            String c = digits(cpf.getText().toString()), p = digits(phone.getText().toString());
            if (c.length() != 11 || (p.length() != 10 && p.length() != 11) ||
                    tech.getText().toString().trim().length() < 2 || !consent.isChecked()) {
                dialog("Preencha CPF, telefone com DDD, identificação do técnico e consentimento."); return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("cpf", c);
                body.put("telefone", p);
                body.put("nome", name.getText().toString());
                body.put("tecnico", tech.getText().toString());
                body.put("consentimento", true);
            } catch (JSONException ignored) {}
            call("/api/v1/interessados", body, response -> {
                initScreen("Enviado ao comercial", "O interesse foi encaminhado com sucesso.", true);
                content.addView(label("Protocolo: " + response.optString("protocolo"), 18, NAVY, true));
            });
        });
        content.addView(send);
    }

    private interface Success { void done(JSONObject response); }

    private void call(String path, JSONObject data, Success onSuccess) {
        if (loading) return;
        if (!BuildConfig.API_BASE.startsWith("https://") || BuildConfig.TERMINAL_KEY.length() < 32) {
            dialog("Configure a URL HTTPS e a chave do terminal no build Android."); return;
        }
        loading = true;
        ProgressBar progress = new ProgressBar(this);
        content.addView(progress);
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(BuildConfig.API_BASE.replaceAll("/+$", "") + path).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(16000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Terminal-Key", BuildConfig.TERMINAL_KEY);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = conn.getResponseCode();
                InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                if (stream == null) throw new IOException("Sem resposta");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] bytes = new byte[8192];
                int count;
                while ((count = stream.read(bytes)) != -1) {
                    buffer.write(bytes, 0, count);
                    if (buffer.size() > 12 * 1024 * 1024) throw new IOException("Resposta muito grande");
                }
                JSONObject json = new JSONObject(buffer.toString("UTF-8"));
                if (status >= 400) throw new IOException(json.optString("detail", "Consulta indisponível"));
                ui.post(() -> { loading = false; if (progress.getParent() != null) content.removeView(progress);
                    onSuccess.done(json); });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "Falha na comunicação" : error.getMessage();
                ui.post(() -> {
                    loading = false;
                    if (progress.getParent() != null) ((LinearLayout) progress.getParent()).removeView(progress);
                    dialog(message);
                });
            } finally { if (conn != null) conn.disconnect(); }
        });
    }

    private void dialog(String message) {
        new AlertDialog.Builder(this).setTitle("ISM Telecom").setMessage(message)
            .setPositiveButton("OK", (d, w) -> {}).show();
    }

    private void closePdf() {
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        renderer = null;
        try { if (pdfDescriptor != null) pdfDescriptor.close(); } catch (Exception ignored) {}
        pdfDescriptor = null;
        if (tempPdf != null) { tempPdf.delete(); tempPdf = null; }
    }

    @Override public void onBackPressed() { showHome(); }
    @Override protected void onDestroy() {
        closePdf(); executor.shutdownNow(); super.onDestroy();
    }
}
