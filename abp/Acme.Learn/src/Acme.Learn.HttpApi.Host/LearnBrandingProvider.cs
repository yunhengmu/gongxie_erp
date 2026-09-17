using Microsoft.Extensions.Localization;
using Acme.Learn.Localization;
using Volo.Abp.DependencyInjection;
using Volo.Abp.Ui.Branding;

namespace Acme.Learn;

[Dependency(ReplaceServices = true)]
public class LearnBrandingProvider : DefaultBrandingProvider
{
    private IStringLocalizer<LearnResource> _localizer;

    public LearnBrandingProvider(IStringLocalizer<LearnResource> localizer)
    {
        _localizer = localizer;
    }

    public override string AppName => _localizer["AppName"];
}
