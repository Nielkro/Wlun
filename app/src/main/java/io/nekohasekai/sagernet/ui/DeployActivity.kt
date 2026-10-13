package app.qthrone.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.*
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class DeployActivity : ThemedActivity() {
    lateinit var toolbar: Toolbar
    var server: DeployServer? = null
    private var shownTrust = ""
    private var shownConfig = ""
    private var wasRunning = false
    val kind get() = intent.getStringExtra("kind").orEmpty().takeIf { it in listOf("qwdtt","csqtt") } ?: "qwdtt"
    private val keyPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri != null) lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true) { val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=65536);output.write(buffer,0,count) };String(output.toByteArray(),Charsets.UTF_8)
                } ?: error("Нет доступа к файлу")
            } }
            text.onSuccess { key -> server?.let { it["key"]=key;it["keyName"]=uri.lastPathSegment.orEmpty();DeployStore.save(it);screen()?.build() } }
                .onFailure { Toast.makeText(this@DeployActivity,"Не удалось прочитать SSH-ключ",Toast.LENGTH_LONG).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownConfig=savedInstanceState?.getString("shownConfig").orEmpty()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(MobilePalette.background) }
        toolbar=Toolbar(this).apply { setTitleTextColor(MobilePalette.text);setNavigationIcon(R.drawable.baseline_arrow_back_24);setNavigationOnClickListener { back() } }
        val content=FrameLayout(this).apply { id=android.R.id.content+3 }
        root.addView(toolbar);root.addView(content,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets -> val b=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(b.left,b.top,b.right,b.bottom);insets }
        val id=savedInstanceState?.getString("server")
        server=DeployStore.all().firstOrNull { it.id==id }
        onBackPressedDispatcher.addCallback(this,object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
        if(savedInstanceState==null) supportFragmentManager.beginTransaction().replace(content.id,Screen()).commit()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DeployEngine.state.collect { state ->
                    screen()?.render(state)
                    if(wasRunning && !state.running && state.percent==100) Toast.makeText(this@DeployActivity,state.step,Toast.LENGTH_LONG).show()
                    wasRunning=state.running
                    if(state.trust.isNotBlank() && state.trust!=shownTrust) {
                        shownTrust=state.trust
                        MobileAlertDialogBuilder(this@DeployActivity).setTitle("Ключ сервера SSH")
                            .setMessage("Сверьте отпечаток с ключом VPS:\n${state.trust}")
                            .setPositiveButton("Доверять") { _,_ -> DeployEngine.approveHost(true) }
                            .setNegativeButton(android.R.string.cancel) { _,_ -> DeployEngine.approveHost(false) }
                            .setOnCancelListener { DeployEngine.approveHost(false) }.show()
                    }
                    if(!state.running && state.config.isNotBlank() && state.config!=shownConfig) {
                        shownConfig=state.config;showConfig(state.config)
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { server?.let { outState.putString("server",it.id) };outState.putString("shownConfig",shownConfig);super.onSaveInstanceState(outState) }
    private fun screen() = supportFragmentManager.fragments.filterIsInstance<Screen>().firstOrNull()
    private fun back() { if(server!=null) { server=null;screen()?.build() } else finish() }
    fun pickKey() { keyPicker.launch("*/*") }
    fun start(uninstall: Boolean) {
        val target=server ?: return
        if(DeployEngine.state.value.running) { Toast.makeText(this,"Дождитесь завершения текущей операции",Toast.LENGTH_SHORT).show();return }
        val valid=runCatching { target.validate(!uninstall) }
        if(valid.isFailure) { MobileAlertDialogBuilder(this).setMessage(valid.exceptionOrNull()?.message).setPositiveButton(android.R.string.ok,null).show();return }
        fun run() { DeployStore.save(target);DeployEngine.start(target,uninstall);shownConfig="";shownTrust="";showConsole() }
        if(uninstall) MobileAlertDialogBuilder(this).setTitle("Удалить сервис с VPS?").setMessage("Будет запущено удаление ${if(kind=="qwdtt") "qWDTT" else "CSQTT"} на ${target["host"]}.")
            .setPositiveButton("Удалить") { _,_ -> run() }.setNegativeButton(android.R.string.cancel,null).show()
        else run()
    }
    private fun showConsole() { screen()?.revealConsole() }
    private fun showConfig(config: String) {
        val text=TextView(this).apply { setTextColor(MobilePalette.text);typeface=android.graphics.Typeface.MONOSPACE;textSize=13f;text=config;setPadding(24,20,24,20);setTextIsSelectable(true) }
        MobileAlertDialogBuilder(this).setTitle("Установка завершена")
            .setView(ScrollView(this).apply { addView(text) })
            .setMessage("Хеши звонков задайте в профиле. Для qWDTT доступно получение через привязанный VK.")
            .setPositiveButton("Скопировать") { _,_ -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("qThrone config",config)) }
            .setNegativeButton("Закрыть",null).show()
    }
    class Screen : DeployEditorFragment()
}
