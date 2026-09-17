using Volo.Abp.Settings;

namespace Acme.Learn.Settings;

public class LearnSettingDefinitionProvider : SettingDefinitionProvider
{
    public override void Define(ISettingDefinitionContext context)
    {
        //Define your own settings here. Example:
        //context.Add(new SettingDefinition(LearnSettings.MySetting1));
    }
}
