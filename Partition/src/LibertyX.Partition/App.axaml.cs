using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using LibertyX.Partition.ViewModels;
using LibertyX.Partition.Views;

namespace LibertyX.Partition;

public partial class App : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var vm = new MainViewModel();
            desktop.MainWindow = new MainWindow { DataContext = vm };
            vm.Start(desktop.Args ?? []);
        }
        base.OnFrameworkInitializationCompleted();
    }
}
