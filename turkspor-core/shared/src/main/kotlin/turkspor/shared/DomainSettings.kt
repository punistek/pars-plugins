package turkspor.shared
import android.content.Context
import turkspor.common.SourceSettings
object DomainSettings {
    fun show(context: Context,resolver: CatalogueController,title: String="TurkSpor") = SourceSettings.show(context,title,{ resolver.currentUrl },{ resolver.checkedAt }) { manual ->
        (if(manual==null) resolver.resolve(true) else resolver.setManual(manual)).channels.size
    }
}
