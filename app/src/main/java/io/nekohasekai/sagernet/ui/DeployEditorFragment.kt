package app.qthrone.mobile.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import androidx.fragment.app.Fragment
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText

/** Compact author-style deployment form with an inline, collapsible VPS console. */
open class DeployEditorFragment : Fragment() {
    private val host get()=requireActivity() as DeployActivity
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private var consoleCard: View?=null
    private var details: View?=null
    private var toggle: MaterialButton?=null
    private var output: TextView?=null
    private var logScroll: ScrollView?=null
    private var status: TextView?=null
    private var progress: ProgressBar?=null
    private var stop: MaterialButton?=null
    private var expanded=true
    private val operations=mutableListOf<View>()
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
    override fun onCreateView(inflater: android.view.LayoutInflater,container: ViewGroup?,state: Bundle?): View {
        expanded=state?.getBoolean("consoleExpanded") ?: true
        scroll=ScrollView(requireContext()).apply { isFillViewport=true;setBackgroundColor(MobilePalette.background) }
        body=column().apply { setPadding(dp(16),dp(8),dp(16),dp(24)) };scroll.addView(body);return scroll
    }
    override fun onViewCreated(view: View,state: Bundle?) { super.onViewCreated(view,state);build() }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("consoleExpanded",expanded);super.onSaveInstanceState(out) }
    private fun heading(parent: LinearLayout,title: String) {
        parent.addView(TextView(requireContext()).apply { text=title;textSize=16f;setTextColor(MobilePalette.accent(requireContext()));setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,dp(10),0,dp(8)) })
    }
    private fun card(): LinearLayout {
        val content=column().apply { setPadding(dp(16),dp(10),dp(16),dp(10)) }
        body.addView(MaterialCardView(requireContext()).apply { radius=dp(20).toFloat();cardElevation=0f;strokeWidth=0;setCardBackgroundColor(MobilePalette.surface);addView(content) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        return content
    }
    private fun row(parent: LinearLayout,block: (LinearLayout)->Unit) {
        val r=LinearLayout(requireContext()).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL };parent.addView(r);block(r)
    }
    private fun button(parent: LinearLayout,title: String,icon: Int=0,pair: Boolean=false,click: ()->Unit): MaterialButton {
        val b=MaterialButton(requireContext()).apply {
            text=title;textSize=14f;isAllCaps=false;maxLines=3;minimumHeight=dp(48);cornerRadius=dp(16)
            setTextColor(MobilePalette.accent(requireContext()));backgroundTintList=android.content.res.ColorStateList.valueOf(MobilePalette.surface)
            if(icon!=0) { setIconResource(icon);iconSize=dp(22);iconTint=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext())) }
            setOnClickListener { click() }
        }
        parent.addView(b,(if(pair) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2)).apply { if(pair) marginEnd=dp(4) })
        return b
    }
    private fun field(parent: LinearLayout,s: DeployServer,key: String,label: String,secret: Boolean=false,number: Boolean=false,pair: Boolean=false) {
        val box=TextInputLayout(requireContext()).apply {
            hint=label;boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(14).toFloat(),dp(14).toFloat(),dp(14).toFloat(),dp(14).toFloat())
            defaultHintTextColor=android.content.res.ColorStateList.valueOf(MobilePalette.muted)
            if(secret) endIconMode=TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val editor=TextInputEditText(box.context).apply {
            textSize=16f;setTextColor(MobilePalette.text);setSingleLine(true)
            inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else if(number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setText(s[key]);setPadding(dp(12),dp(16),dp(12),dp(16))
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(value: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun afterTextChanged(value: Editable?) {}
                override fun onTextChanged(value: CharSequence?,start: Int,before: Int,count: Int) {
                    if(s[key]==value.toString()) return
                    s[key]=value?.toString().orEmpty();if(key in listOf("host","sshPort")) s["hostKey"]=""
                    DeployStore.save(s)
                }
            })
        }
        box.addView(editor,ViewGroup.LayoutParams(-1,-2))
        parent.addView(box,(if(pair) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2)).apply { topMargin=dp(6);bottomMargin=dp(6);if(pair) marginEnd=dp(4) })
    }
    private fun switch(parent: LinearLayout,s: DeployServer,key: String,label: String,rebuild: Boolean=false) {
        parent.addView(SwitchMaterial(requireContext()).apply {
            text=label;textSize=15f;setTextColor(MobilePalette.text);minHeight=dp(48);isChecked=s[key]=="true"
            setOnCheckedChangeListener { _,checked -> s[key]=checked.toString();DeployStore.save(s);if(rebuild) build() }
        },LinearLayout.LayoutParams(-1,-2))
    }
    private fun secrets(s: DeployServer) {
        val c=column().apply { setPadding(dp(20),dp(8),dp(20),dp(16)) }
        field(c,s,"password","Пароль туннеля",secret=true)
        if(s.kind=="qwdtt") {
            heading(c,"Telegram-бот (необязательно)")
            field(c,s,"adminId","ID администратора",number=true);field(c,s,"botToken","Токен бота",secret=true)
        } else {
            heading(c,"Доступ к веб-панели")
            field(c,s,"webUser","Логин WEB");field(c,s,"webPassword","Пароль WEB",secret=true)
        }
        heading(c,"SSH");field(c,s,"sshPort","Порт SSH",number=true)
        MobileAlertDialogBuilder(requireContext()).setTitle(if(s.kind=="qwdtt") "Секреты деплоя" else "Авторизация")
            .setView(ScrollView(requireContext()).apply { addView(c) }).setPositiveButton("Готово",null).show()
    }
    fun build() {
        if(!this::body.isInitialized) return
        body.removeAllViews();host.toolbar.menu.clear();operations.clear()
        output=null;logScroll=null;status=null;progress=null;stop=null;consoleCard=null;details=null;toggle=null
        val s=host.server
        host.toolbar.title=if(s==null) "Деплой "+(if(host.kind=="qwdtt") "qWDTT" else "CSQTT") else "Управление сервером"
        if(s==null) {
            host.toolbar.menu.add("Добавить сервер").apply {
                setIcon(R.drawable.ic_mobile_add);setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { host.server=DeployServer.create(host.kind);DeployStore.save(host.server!!);build();true }
            }
            for(item in DeployStore.all().filter { it.kind==host.kind }) button(card(),item["name"]+"\n"+item["host"].ifBlank { "Укажите адрес VPS" },R.drawable.ic_mobile_core) { host.server=item;build() }
            if(body.childCount==0) heading(body,"Добавьте VPS кнопкой +")
            return
        }
        host.toolbar.menu.add("Сохранить").apply { setIcon(R.drawable.ic_mobile_apply);setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);setOnMenuItemClickListener { DeployStore.save(s);Toast.makeText(context,"Сервер сохранён",Toast.LENGTH_SHORT).show();true } }
        host.toolbar.menu.add("Удалить из списка").setOnMenuItemClickListener {
            MobileAlertDialogBuilder(requireContext()).setMessage("Удалить сохранённые данные? Сервис на VPS останется.")
                .setPositiveButton("Удалить") { _,_ -> DeployStore.remove(s.id);host.server=null;build() }.setNegativeButton(android.R.string.cancel,null).show();true
        }
        button(body,if(s.kind=="qwdtt") "Секреты (бот, пароли)" else "Авторизация",R.drawable.ic_baseline_vpn_key_24) { secrets(s) }
        row(body) { r ->
            operations+=button(r,"Установить",R.drawable.ic_mobile_core,true) { host.start(false) }
            operations+=button(r,"Удалить",R.drawable.ic_mobile_delete,true) { host.start(true) }
        }
        val access=card()
        field(access,s,"name","Название сервера")
        row(access) { r -> field(r,s,"host","IP / домен",pair=true);field(r,s,"user","Логин SSH",pair=true) }
        heading(access,"Вход на сервер")
        row(access) { r ->
            for((value,label) in listOf("password" to "Пароль","key" to "SSH-ключ")) button(r,label,pair=true) { s["auth"]=value;DeployStore.save(s);build() }.apply {
                strokeWidth=dp(1);strokeColor=android.content.res.ColorStateList.valueOf(if(s["auth"]==value) MobilePalette.accent(requireContext()) else MobilePalette.muted)
            }
        }
        if(s["auth"]=="key") {
            row(access) { r ->
                button(r,if(s["key"].isBlank()) "Загрузить ключ" else "Сменить ключ",pair=true) { host.pickKey() }
                if(s["key"].isNotBlank()) button(r,"Удалить",R.drawable.ic_mobile_delete,true) { s["key"]="";s["keyName"]="";DeployStore.save(s);build() }
            }
            if(s["key"].isNotBlank()) heading(access,"Ключ загружен: "+s["keyName"])
            field(access,s,"keyPassword","Пароль SSH-ключа",secret=true);field(access,s,"sudoPassword","Пароль sudo (если требуется)",secret=true)
        } else field(access,s,"sshPassword","Пароль SSH",secret=true)
        if(s.kind=="qwdtt") row(access) { r -> field(r,s,"dns1","DNS 1",pair=true);field(r,s,"dns2","DNS 2",pair=true) }
        switch(access,s,"manualPorts","Ручное управление портами",true)
        if(s["manualPorts"]=="true") {
            row(access) { r -> field(r,s,"peerPort","Порт туннеля",number=true,pair=true);field(r,s,if(s.kind=="qwdtt") "wgPort" else "webPort",if(s.kind=="qwdtt") "Порт WG" else "Порт WEB",number=true,pair=true) }
            if(s.kind=="qwdtt") {
                row(access) { r -> field(r,s,"rawPort","Порт RAW",number=true,pair=true);field(r,s,"directPort","Прямой порт",number=true,pair=true) }
                field(access,s,"adminPort","Порт админ-панели",number=true)
            }
        }
        if(s.kind=="csqtt") {
            switch(access,s,"docker","Установить в Docker")
            button(body,"Открыть веб-панель",R.drawable.ic_mobile_globe) {
                runCatching {
                    require(s["host"].isNotBlank());val address=s["host"].let { if(it.contains(':')) "[$it]" else it }
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://"+address+":"+s.port("webPort"))))
                }.onFailure { Toast.makeText(context,"Укажите адрес сервера",Toast.LENGTH_SHORT).show() }
            }
        }
        val console=card();consoleCard=console.parent as View
        toggle=button(console,"Консоль") { expanded=!expanded;expand() }
        val content=column();console.addView(content);details=content
        status=TextView(requireContext()).apply { setTextColor(MobilePalette.text);textSize=14f };content.addView(status)
        progress=ProgressBar(requireContext(),null,android.R.attr.progressBarStyleHorizontal);content.addView(progress,LinearLayout.LayoutParams(-1,dp(16)))
        output=TextView(requireContext()).apply { setTextColor(MobilePalette.text);typeface=android.graphics.Typeface.MONOSPACE;textSize=12f;setTextIsSelectable(true);setPadding(0,dp(8),0,dp(8)) }
        logScroll=ScrollView(requireContext()).apply { addView(output) }
        content.addView(logScroll,LinearLayout.LayoutParams(-1,dp(220)))
        stop=button(content,"Остановить") { DeployEngine.cancel() }
        expand();render(DeployEngine.state.value)
    }
    private fun expand() { details?.visibility=if(expanded) View.VISIBLE else View.GONE;toggle?.text=if(expanded) "Консоль · свернуть" else "Консоль · развернуть" }
    fun revealConsole() { expanded=true;expand();scroll.post { consoleCard?.let { scroll.smoothScrollTo(0,it.top) } } }
    fun render(state: DeployState) {
        val relevant=state.serverId==host.server?.id
        status?.text=if(relevant) state.step+(if(state.running && !state.installerProgress && state.percent in 45..99) "" else " · "+state.percent+"%") else "Готово к подключению"
        val logs=if(relevant) state.logs.ifBlank { "Здесь появятся логи VPS" } else "Здесь появятся логи VPS"
        if(output?.text?.toString()!=logs) {
            val follow=logScroll?.let { !it.canScrollVertically(1) } ?: false
            output?.text=logs
            if(follow) logScroll?.post { logScroll?.smoothScrollTo(0,output?.height ?: 0) }
        }
        progress?.progress=if(relevant) state.percent else 0
        progress?.isIndeterminate=relevant && state.running && !state.installerProgress && state.percent in 45..99
        stop?.visibility=if(relevant && state.running) View.VISIBLE else View.GONE
        operations.forEach { it.isEnabled=!state.running }
    }
    override fun onDestroyView() { output=null;logScroll=null;status=null;progress=null;stop=null;consoleCard=null;details=null;toggle=null;operations.clear();super.onDestroyView() }
}

