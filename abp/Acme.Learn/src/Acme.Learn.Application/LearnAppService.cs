using Acme.Learn.Localization;
using Volo.Abp.Application.Services;

namespace Acme.Learn;

/* Inherit your application services from this class.
 */
public abstract class LearnAppService : ApplicationService
{
    protected LearnAppService()
    {
        LocalizationResource = typeof(LearnResource);
    }
}
