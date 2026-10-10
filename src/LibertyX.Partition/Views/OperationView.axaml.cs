using Avalonia.Controls;
using LibertyX.Partition.ViewModels;

namespace LibertyX.Partition.Views;

public partial class OperationView : UserControl
{
    public OperationView()
    {
        InitializeComponent();
        DataContextChanged += (_, _) =>
        {
            if (DataContext is OperationViewModel vm)
                vm.Log.CollectionChanged += (_, _) => Avalonia.Threading.Dispatcher.UIThread.Post(() => LogScroll.ScrollToEnd());
        };
    }
}
