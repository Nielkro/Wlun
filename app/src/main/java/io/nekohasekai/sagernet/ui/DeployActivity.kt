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
    private var console: MobileSheetDialog? = null
    private var consoleText: TextView? = null
    private var stepText: TextView? = null
    private var progress: ProgressBar? = null
    private var shownTrust = ""
    private var shownConfig = ""
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
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(MobilePalette.background) }
        toolbar=Toolbar(this).apply { setTitleTextColor(MobilePalette.text);setNavigationIcon(R.drawable.baseline_arrow_back_24);setNavigationOnClickListener { back() } }
        val content=FrameLayout(this).apply { id=android.R.id.content+3 }
        root.addView(toolbar);root.addView(content,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets -> val b=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(b.left,b.top,b.right,b.bottom);insets }
        val id=savedInstanceState?.getString("server")
        server=DeployStore.all().firstOrNull { it.id==id }
        if(savedInstanceState==null) supportFragmentManager.beginTransaction().replace(content.id,Screen()).commit()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DeployEngine.state.collect { state ->
                    stepText?.text="${state.step} · ${state.percent}%"
                    progress?.progress=state.percent
                    progress?.isIndeterminate=state.running && state.percent in 45..99
                    consoleText?.text=state.logs
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
    override fun onSaveInstanceState(outState: Bundle) { server?.let { outState.putString("server",it.id) };super.onSaveInstanceState(outState) }
    private fun screen() = supportFragmentManager.fragments.filterIsInstance<Screen>().firstOrNull()
    private fun back() { if(server!=null) { server=null;screen()?.build() } else finish() }
    fun pickKey() { keyPicker.launch("*/*") }
    fun start(uninstall: Boolean) {
        val target=server ?: return
        if(DeployEngine.state.value.running) { showConsole();return }
        val valid=runCatching { target.validate(!uninstall) }
        if(valid.isFailure) { MobileAlertDialogBuilder(this).setMessage(valid.exceptionOrNull()?.message).setPositiveButton(android.R.string.ok,null).show();return }
        fun run() { DeployStore.save(target);DeployEngine.start(target,uninstall);shownConfig="";shownTrust="";showConsole() }
        if(uninstall) MobileAlertDialogBuilder(this).setTitle("Удалить сервис с VPS?").setMessage("Будет запущено удаление ${if(kind=="qwdtt") "qWDTT" else "CSQTT"} на ${target["host"]}.")
            .setPositiveButton("Удалить") { _,_ -> run() }.setNegativeButton(android.R.string.cancel,null).show()
        else run()
    }
    private fun showConsole() {
        if(console?.isShowing==true) return
        val dialog=MobileSheetDialog(this);console=dialog
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,20,24,24) }
        stepText=TextView(this).apply { setTextColor(MobilePalette.text);textSize=17f };body.addView(stepText)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);body.addView(progress,LinearLayout.LayoutParams(-1,24))
        consoleText=TextView(this).apply { setTextColor(MobilePalette.text);typeface=android.graphics.Typeface.MONOSPACE;textSize=12f;setTextIsSelectable(true) }
        body.addView(ScrollView(this).apply { addView(consoleText) },LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.5).toInt()))
        body.addView(Button(this).apply { text="Остановить";setTextColor(MobilePalette.accent(this@DeployActivity));setOnClickListener { DeployEngine.cancel() } })
        dialog.setContentView(body);dialog.setOnDismissListener { consoleText=null;stepText=null;progress=null;console=null };dialog.show()
        val state=DeployEngine.state.value;consoleText?.text=state.logs;stepText?.text=state.step;progress?.progress=state.percent
    }
    private fun showConfig(config: String) {
        val text=TextView(this).apply { setTextColor(MobilePalette.text);typeface=android.graphics.Typeface.MONOSPACE;textSize=13f;text=config;setPadding(24,20,24,20);setTextIsSelectable(true) }
        MobileAlertDialogBuilder(this).setTitle("Конфигурация подключения")
            .setView(ScrollView(this).apply { addView(text) })
            .setMessage("Хеши звонков задаются в профиле вручную или получаются через привязанный VK.")
            .setPositiveButton("Скопировать") { _,_ -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("qThrone config",config)) }
            .setNegativeButton("Закрыть",null).show()
    }
    class Screen : PreferenceFragmentCompat() {
        private val host get() = requireActivity() as DeployActivity
        override fun onCreatePreferences(state: Bundle?,key: String?) { preferenceScreen=preferenceManager.createPreferenceScreen(requireContext());build() }
        override fun onViewCreated(view: View,state: Bundle?) { super.onViewCreated(view,state);MobileSettingsStyle.apply(this,view) }
        override fun onDisplayPreferenceDialog(preference: Preference) { if(preference is EditTextPreference) MobilePreferenceDialog.show(this,preference) else super.onDisplayPreferenceDialog(preference) }
        fun build() {
            preferenceScreen.removeAll();host.toolbar.menu.clear()
            val server=host.server
            host.toolbar.title=if(server==null) "Деплой ${if(host.kind=="qwdtt") "qWDTT" else "CSQTT"}" else "Управление сервером"
            if(server==null) {
                host.toolbar.menu.add("Добавить сервер").apply { setIcon(R.drawable.ic_mobile_add);setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);setOnMenuItemClickListener { host.server=DeployServer.create(host.kind);DeployStore.save(host.server!!);build();true } }
                for(item in DeployStore.all().filter { it.kind==host.kind }) preferenceScreen.addPreference(Preference(requireContext()).apply {
                    title=item["name"];summary=item["host"].ifBlank { "Укажите адрес VPS" };setIcon(R.drawable.ic_mobile_core);widgetLayoutResource=R.layout.qmobile_preference_arrow
                    setOnPreferenceClickListener { host.server=item;build();true }
                })
                if(preferenceScreen.preferenceCount==0) preferenceScreen.addPreference(Preference(requireContext()).apply { summary="Добавьте VPS кнопкой +. Поддерживаются пароль и SSH-ключ.";isSelectable=false })
                return
            }
            host.toolbar.menu.add("Сохранить").apply { setIcon(R.drawable.ic_mobile_apply);setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);setOnMenuItemClickListener { DeployStore.save(server);Toast.makeText(context,"Сервер сохранён",Toast.LENGTH_SHORT).show();true } }
            host.toolbar.menu.add("Удалить из списка").setOnMenuItemClickListener {
                MobileAlertDialogBuilder(requireContext()).setMessage("Удалить сохранённые данные сервера? Сервис на VPS останется.").setPositiveButton("Удалить") { _,_ -> DeployStore.remove(server.id);host.server=null;build() }.setNegativeButton(android.R.string.cancel,null).show();true
            }
            fun category(title: String): PreferenceCategory = PreferenceCategory(requireContext()).apply { this.title=title;preferenceScreen.addPreference(this) }
            fun action(group: PreferenceGroup,title: String,icon: Int=R.drawable.ic_mobile_chevron,block: () -> Unit) { group.addPreference(Preference(requireContext()).apply { this.title=title;setIcon(icon);setOnPreferenceClickListener { block();true } }) }
            fun field(group: PreferenceGroup,key: String,title: String,secret: Boolean=false,numeric: Boolean=false) {
                group.addPreference(EditTextPreference(requireContext()).apply {
                    this.key=key;this.title=title;dialogTitle=title;isPersistent=false;setDefaultValue(server[key])
                    summaryProvider=Preference.SummaryProvider<EditTextPreference> { if(secret && server[key].isNotEmpty()) "Задано" else server[key].ifBlank { "Не указано" } }
                    setOnBindEditTextListener { it.inputType=if(secret) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else if(numeric) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT }
                    setOnPreferenceChangeListener { _,value -> server[key]=value.toString();if(key in listOf("host","sshPort")) server["hostKey"]="";DeployStore.save(server);true }
                }.also { groupPreference -> groupPreference.text=server[key] })
            }
            fun switch(group: PreferenceGroup,key: String,title: String,changed: () -> Unit = {}) {
                group.addPreference(SwitchPreference(requireContext()).apply { this.title=title;isPersistent=false;isChecked=server[key]=="true";setOnPreferenceChangeListener { _,value -> server[key]=value.toString();DeployStore.save(server);changed();true } })
            }
            val access=category("Сервер и SSH")
            field(access,"name","Название");field(access,"host","IP или домен");field(access,"user","Логин SSH");field(access,"sshPort","Порт SSH",numeric=true)
            access.addPreference(SimpleMenuPreference(requireContext()).apply {
                title="Вход на сервер";isPersistent=false;entries=arrayOf("Пароль","SSH-ключ");entryValues=arrayOf("password","key");setDefaultValue(server["auth"])
                summaryProvider=ListPreference.SimpleSummaryProvider.getInstance();setOnPreferenceChangeListener { _,value -> server["auth"]=value.toString();DeployStore.save(server);build();true }
            }.also { it.value=server["auth"] })
            if(server["auth"]=="key") {
                action(access,if(server["key"].isBlank()) "Загрузить SSH-ключ" else "Сменить SSH-ключ") { host.pickKey() }
                if(server["key"].isNotBlank()) action(access,"Удалить SSH-ключ",R.drawable.ic_mobile_delete) { server["key"]="";server["keyName"]="";DeployStore.save(server);build() }
                field(access,"keyPassword","Пароль SSH-ключа",secret=true)
                field(access,"sudoPassword","Пароль sudo (если требуется)",secret=true)
            } else field(access,"sshPassword","Пароль SSH",secret=true)
            val secrets=category(if(host.kind=="qwdtt") "Секреты деплоя" else "Авторизация")
            field(secrets,"password","Пароль туннеля",secret=true)
            if(host.kind=="qwdtt") {
                field(secrets,"adminId","ID администратора Telegram (необязательно)",numeric=true)
                field(secrets,"botToken","Токен Telegram-бота (необязательно)",secret=true)
                field(secrets,"dns1","DNS 1");field(secrets,"dns2","DNS 2")
            } else { field(secrets,"webUser","Логин веб-панели");field(secrets,"webPassword","Пароль веб-панели",secret=true) }
            val options=category("Настройки установки")
            switch(options,"manualPorts","Ручное управление портами") { build() }
            if(server["manualPorts"]=="true") {
                field(options,"peerPort","Порт туннеля",numeric=true)
                if(host.kind=="qwdtt") { field(options,"wgPort","Порт WG",numeric=true);field(options,"rawPort","Порт RAW",numeric=true);field(options,"adminPort","Порт админ-панели",numeric=true) }
                else field(options,"webPort","Порт веб-панели",numeric=true)
            }
            if(host.kind=="csqtt") switch(options,"docker","Установить в Docker")
            val operations=category("Действия")
            action(operations,"Установить",R.drawable.ic_mobile_core) { host.start(false) }
            action(operations,"Удалить сервис с VPS",R.drawable.ic_mobile_delete) { host.start(true) }
            action(operations,"Консоль и статус") { host.showConsole() }
            if(host.kind=="csqtt") action(operations,"Открыть веб-панель",R.drawable.ic_mobile_globe) {
                runCatching { val address=server["host"].let { if(it.contains(':')) "[$it]" else it };startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://$address:${server.port("webPort")}"))) }
                    .onFailure { Toast.makeText(context,"Укажите адрес сервера",Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
