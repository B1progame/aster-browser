import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCH = ROOT / 'patches/m151/0040-aster-settings-engine-handoff.patch'


def added_source():
    return '\n'.join(line[1:] for line in PATCH.read_text().splitlines()
                     if line.startswith('+') and not line.startswith('+++'))


class SettingsHandoffTests(unittest.TestCase):
    def test_settings_actions_target_the_right_activity(self):
        added = added_source()
        self.assertIn('new Intent(activity, app.aster.browser.MainActivity.class)', added)
        self.assertIn('activity, app.aster.browser.ChromiumFeaturesActivity.class', added)
        self.assertIn('launchIntent.putExtra("aster_feature", "tools")', PATCH.read_text())
        self.assertNotIn('(org.chromium.chrome.browser.app.ChromeActivity) activity', added)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_tab_handoff_rejects_private_ambiguous_and_unready_sources(self):
        added = added_source()
        begin = added.index('    private static org.chromium.chrome.browser.@Nullable ChromeTabbedActivity asterFindBrowser(')
        end = added.index('\n    }', added.index('intent.putExtra("aster_active_tab", active);', begin)) + len('\n    }')
        helpers = added[begin:end]
        sources = {
            'android/app/Activity.java': '''package android.app;
public class Activity { public int task; public boolean finishing, destroyed;
 public Activity(int task) { this.task=task; } public int getTaskId(){return task;}
 public boolean isFinishing(){return finishing;} public boolean isDestroyed(){return destroyed;} }''',
            'android/content/Intent.java': '''package android.content; import java.util.*;
public class Intent { public Map<String,Object> extras=new HashMap<>();
 public void putStringArrayListExtra(String k, ArrayList<String> v){extras.put(k,v);}
 public void putExtra(String k,int v){extras.put(k,v);} }''',
            'org/chromium/build/annotations/Nullable.java': '''package org.chromium.build.annotations;
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
public @interface Nullable {}''',
            'org/chromium/base/ApplicationStatus.java': '''package org.chromium.base; import android.app.Activity; import java.util.*;
public class ApplicationStatus { public static List<Activity> activities=new ArrayList<>();
 public static List<Activity> getRunningActivities(){return activities;} }''',
            'org/chromium/chrome/browser/tab/Tab.java': '''package org.chromium.chrome.browser.tab;
public class Tab { public static class Url { String value; Url(String s){value=s;} public String getSpec(){return value;} }
 public Url url; public String title; public Tab(String u,String t){url=u==null?null:new Url(u);title=t;}
 public Url getUrl(){return url;} public String getTitle(){return title;} }''',
            'org/chromium/chrome/browser/tabmodel/TabModel.java': '''package org.chromium.chrome.browser.tabmodel;
import java.util.*; import org.chromium.chrome.browser.tab.Tab;
public class TabModel { public List<Tab> tabs=new ArrayList<>(); public int selected;
 public int getCount(){return tabs.size();} public Tab getTabAt(int i){return tabs.get(i);} public int index(){return selected;} }''',
            'org/chromium/chrome/browser/tabmodel/TabModelSelector.java': '''package org.chromium.chrome.browser.tabmodel;
public class TabModelSelector { public boolean ready=true, isPrivate; public TabModel model=new TabModel();
 public boolean isTabStateInitialized(){return ready;} public boolean isOffTheRecordModelSelected(){return isPrivate;}
 public TabModel getModel(boolean incognito){if(incognito)throw new AssertionError();return model;} }''',
            'org/chromium/chrome/browser/ChromeTabbedActivity.java': '''package org.chromium.chrome.browser;
import android.app.Activity; import org.chromium.chrome.browser.tabmodel.TabModelSelector;
public class ChromeTabbedActivity extends Activity { public boolean ready=true; public TabModelSelector selector=new TabModelSelector();
 public ChromeTabbedActivity(int task){super(task);} public boolean areTabModelsInitialized(){return ready;}
 public TabModelSelector getTabModelSelector(){return selector;} }''',
        }
        harness = '''import android.app.Activity;
import android.content.Intent;
import org.chromium.build.annotations.Nullable;
import org.chromium.base.ApplicationStatus;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.tab.Tab;
import java.util.*;
public class HandoffTest {
HELPERS
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] ignored){
  Activity settings=new Activity(7); ChromeTabbedActivity browser=new ChromeTabbedActivity(7);
  browser.selector.model.tabs.add(new Tab("chrome://newtab", "Internal"));
  browser.selector.model.tabs.add(new Tab("https://example.test/a", "Example"));
  browser.selector.model.tabs.add(new Tab("http://example.test/b", null));
  browser.selector.model.selected=2; ApplicationStatus.activities.add(settings);
  ApplicationStatus.activities.add(browser);
  check(asterFindBrowser(settings)==browser,"matching browser");
  Intent out=new Intent(); asterAttachRegularTabs(out,browser);
  check(out.extras.get("aster_tabs").equals(Arrays.asList("https://example.test/a","http://example.test/b")),"normal URLs");
  check(out.extras.get("aster_titles")==null,"no unintended extra");
  check(out.extras.get("aster_tab_titles").equals(Arrays.asList("Example","http://example.test/b")),"titles");
  check(out.extras.get("aster_active_tab").equals(1),"selected index");
  browser.selector.isPrivate=true; out=new Intent(); asterAttachRegularTabs(out,browser);
  check(out.extras.isEmpty(),"off-record must not transfer"); browser.selector.isPrivate=false;
  browser.selector.ready=false; out=new Intent(); asterAttachRegularTabs(out,browser);
  check(out.extras.isEmpty(),"unready tab state"); browser.selector.ready=true;
  browser.ready=false; out=new Intent(); asterAttachRegularTabs(out,browser);
  check(out.extras.isEmpty(),"unready browser"); browser.ready=true;
  ChromeTabbedActivity other=new ChromeTabbedActivity(8); ApplicationStatus.activities.add(other);
  check(asterFindBrowser(settings)==browser,"other task ignored");
  ChromeTabbedActivity duplicate=new ChromeTabbedActivity(7); ApplicationStatus.activities.add(duplicate);
  check(asterFindBrowser(settings)==null,"ambiguous task rejected");
  duplicate.destroyed=true; check(asterFindBrowser(settings)==browser,"destroyed ignored");
  browser.finishing=true; check(asterFindBrowser(settings)==null,"finishing ignored");
 }
}'''.replace('HELPERS', helpers)
        sources['HandoffTest.java'] = harness
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            files = []
            for name, content in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                files.append(str(path))
            subprocess.run(['javac', '-d', str(root / 'classes'), *files], check=True, capture_output=True, text=True)
            subprocess.run(['java', '-cp', str(root / 'classes'), 'HandoffTest'], check=True, capture_output=True, text=True)


if __name__ == '__main__':
    unittest.main()
